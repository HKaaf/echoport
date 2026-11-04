//! Server side of the protocol: accepts players over TCP and streams frames over UDP.

use anyhow::{bail, Result};
use serde::Serialize;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::{broadcast, watch};
use tokio::time::{interval, timeout, Instant};

use crate::protocol::*;

pub type Frame = Arc<Vec<i16>>;

#[derive(Debug, Clone)]
pub struct ServerConfig {
    pub id: String,
    pub name: String,
    pub port: u16,
    pub pin: Option<String>,
    pub source: Source,
}

#[derive(Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct PeerInfo {
    pub id: String,
    pub name: String,
    pub platform: String,
    pub addr: String,
    pub rtt_ms: u32,
}

struct Shared {
    cfg: Mutex<ServerConfig>,
    peers: Mutex<HashMap<u32, PeerInfo>>,
    udp_targets: Mutex<HashMap<u32, SocketAddr>>,
}

pub struct Server {
    shared: Arc<Shared>,
    frames: broadcast::Sender<Frame>,
    source_tx: watch::Sender<Source>,
    kick_tx: broadcast::Sender<String>,
    shutdown: watch::Sender<bool>,
}

impl Server {
    pub async fn start(cfg: ServerConfig) -> Result<Server> {
        let tcp = TcpListener::bind(("0.0.0.0", cfg.port)).await?;
        let udp = Arc::new(UdpSocket::bind(("0.0.0.0", cfg.port)).await?);
        let (frames, _) = broadcast::channel(64);
        let (source_tx, _) = watch::channel(cfg.source);
        let (kick_tx, _) = broadcast::channel(8);
        let (shutdown, _) = watch::channel(false);
        let shared = Arc::new(Shared {
            cfg: Mutex::new(cfg),
            peers: Default::default(),
            udp_targets: Default::default(),
        });

        // UDP "hi" packets tell us where each player wants its audio.
        {
            let (udp, shared, mut stop) = (udp.clone(), shared.clone(), shutdown.subscribe());
            tokio::spawn(async move {
                let mut buf = [0u8; 64];
                loop {
                    tokio::select! {
                        r = udp.recv_from(&mut buf) => {
                            let Ok((n, from)) = r else { continue };
                            if let Some(h) = Header::parse(&buf[..n]) {
                                if h.kind == KIND_HI && shared.peers.lock().unwrap().contains_key(&h.token) {
                                    shared.udp_targets.lock().unwrap().insert(h.token, from);
                                }
                            }
                        }
                        _ = stop.changed() => break,
                    }
                }
            });
        }

        {
            let (shared, frames, source_tx, kick_tx, shutdown_rx) =
                (shared.clone(), frames.clone(), source_tx.clone(), kick_tx.clone(), shutdown.subscribe());
            tokio::spawn(async move {
                let mut stop = shutdown_rx.clone();
                loop {
                    tokio::select! {
                        r = tcp.accept() => {
                            let Ok((stream, addr)) = r else { continue };
                            let ctx = PeerCtx {
                                shared: shared.clone(),
                                udp: udp.clone(),
                                frames: frames.subscribe(),
                                source: source_tx.subscribe(),
                                kick: kick_tx.subscribe(),
                                stop: shutdown_rx.clone(),
                            };
                            tokio::spawn(async move {
                                if let Err(e) = serve_peer(stream, addr, ctx).await {
                                    log::info!("player {addr} left: {e}");
                                }
                            });
                        }
                        _ = stop.changed() => break,
                    }
                }
            });
        }

        Ok(Server { shared, frames, source_tx, kick_tx, shutdown })
    }

    /// Where the audio thread sends finished frames (interleaved stereo i16).
    pub fn frame_sender(&self) -> broadcast::Sender<Frame> {
        self.frames.clone()
    }

    pub fn peers(&self) -> Vec<PeerInfo> {
        self.shared.peers.lock().unwrap().values().cloned().collect()
    }

    pub fn kick(&self, peer_id: &str) {
        let _ = self.kick_tx.send(peer_id.to_string());
    }

    pub fn set_source(&self, source: Source) {
        self.shared.cfg.lock().unwrap().source = source;
        let _ = self.source_tx.send(source);
    }

    pub fn set_pin(&self, pin: Option<String>) {
        self.shared.cfg.lock().unwrap().pin = pin;
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        let _ = self.shutdown.send(true);
    }
}

struct PeerCtx {
    shared: Arc<Shared>,
    udp: Arc<UdpSocket>,
    frames: broadcast::Receiver<Frame>,
    source: watch::Receiver<Source>,
    kick: broadcast::Receiver<String>,
    stop: watch::Receiver<bool>,
}

async fn serve_peer(stream: TcpStream, addr: SocketAddr, mut ctx: PeerCtx) -> Result<()> {
    stream.set_nodelay(true)?;
    let (mut rd, mut wr) = stream.into_split();

    let hello = timeout(Duration::from_secs(5), read_msg(&mut rd)).await??;
    let Msg::Hello { proto, id, name, platform, pin } = hello else { bail!("expected hello") };
    let cfg = ctx.shared.cfg.lock().unwrap().clone();
    let refuse = |code: &str, message: &str| Msg::Error { code: code.into(), message: message.into() };
    if proto != PROTO_VERSION {
        write_msg(&mut wr, &refuse("version", "Unsupported protocol version")).await?;
        bail!("version {proto}");
    }
    if let Some(expected) = &cfg.pin {
        match pin {
            None => {
                write_msg(&mut wr, &refuse("pin_required", "This server needs a PIN")).await?;
                bail!("pin required");
            }
            Some(p) if &p != expected => {
                write_msg(&mut wr, &refuse("bad_pin", "Wrong PIN")).await?;
                bail!("bad pin");
            }
            _ => {}
        }
    }

    let token = uuid::Uuid::new_v4().as_u128() as u32;
    let source = *ctx.source.borrow();
    write_msg(&mut wr, &Msg::Welcome {
        proto: PROTO_VERSION,
        id: cfg.id.clone(),
        name: cfg.name.clone(),
        source,
        token,
        udp_port: cfg.port,
        format: AudioFormat::default(),
    }).await?;
    ctx.shared.peers.lock().unwrap().insert(token, PeerInfo {
        id: id.clone(),
        name,
        platform,
        addr: addr.ip().to_string(),
        rtt_ms: 0,
    });
    log::info!("player {addr} joined");

    // Audio pump for this player.
    let (shared, udp, mut frames) = (ctx.shared.clone(), ctx.udp.clone(), ctx.frames);
    let pump = tokio::spawn(async move {
        let mut seq: u32 = 0;
        let mut packet = Vec::with_capacity(HEADER_LEN + FRAME_SAMPLES * 4);
        loop {
            match frames.recv().await {
                Ok(frame) => {
                    let target = shared.udp_targets.lock().unwrap().get(&token).copied();
                    if let Some(to) = target {
                        encode_audio(token, seq, &frame, &mut packet);
                        let _ = udp.send_to(&packet, to).await;
                        seq = seq.wrapping_add(1);
                    }
                }
                Err(broadcast::error::RecvError::Lagged(_)) => continue,
                Err(_) => break,
            }
        }
    });

    let result: Result<()> = async {
        let mut tick = interval(Duration::from_secs(1));
        let mut last_seen = Instant::now();
        loop {
            tokio::select! {
                msg = read_msg(&mut rd) => {
                    last_seen = Instant::now();
                    match msg? {
                        Msg::Ping { t } => write_msg(&mut wr, &Msg::Pong { t }).await?,
                        Msg::Pong { t } => {
                            if let Some(p) = ctx.shared.peers.lock().unwrap().get_mut(&token) {
                                p.rtt_ms = now_ms().saturating_sub(t) as u32;
                            }
                        }
                        Msg::Bye => return Ok(()),
                        _ => {}
                    }
                }
                _ = tick.tick() => {
                    if last_seen.elapsed() > Duration::from_secs(5) {
                        bail!("timed out");
                    }
                    write_msg(&mut wr, &Msg::Ping { t: now_ms() }).await?;
                }
                Ok(()) = ctx.source.changed() => {
                    write_msg(&mut wr, &Msg::Format(AudioFormat::default())).await?;
                }
                Ok(kicked) = ctx.kick.recv() => {
                    if kicked == id {
                        let _ = write_msg(&mut wr, &Msg::Bye).await;
                        return Ok(());
                    }
                }
                _ = ctx.stop.changed() => {
                    let _ = write_msg(&mut wr, &Msg::Bye).await;
                    return Ok(());
                }
            }
        }
    }.await;

    pump.abort();
    ctx.shared.peers.lock().unwrap().remove(&token);
    ctx.shared.udp_targets.lock().unwrap().remove(&token);
    result
}
