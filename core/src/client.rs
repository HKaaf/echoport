//! Player side of the protocol: connects to a server and feeds a jitter buffer.

use anyhow::{anyhow, bail, Result};
use serde::Serialize;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::net::{TcpStream, UdpSocket};
use tokio::sync::watch;
use tokio::time::{interval, timeout, Instant};

use crate::dsp::from_i16;
use crate::jitter::JitterBuffer;
use crate::protocol::*;

#[derive(Debug, Clone)]
pub struct ClientConfig {
    pub id: String,
    pub name: String,
    pub platform: String,
    pub pin: Option<String>,
    pub buffer_ms: u32,
}

/// The server turned the player away; `code` is one of the protocol error codes.
#[derive(Debug, Clone)]
pub struct Refused {
    pub code: String,
    pub message: String,
}

impl std::fmt::Display for Refused {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.message)
    }
}

impl std::error::Error for Refused {}

#[derive(Serialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct ClientStatus {
    pub connected: bool,
    pub server_id: String,
    pub server_name: String,
    pub source: Option<Source>,
    pub rtt_ms: u32,
    pub lost: u64,
    pub underruns: u64,
    pub buffered_ms: u32,
}

pub struct Client {
    jitter: Arc<Mutex<JitterBuffer>>,
    status: Arc<Mutex<ClientStatus>>,
    stop: watch::Sender<bool>,
}

impl Client {
    /// Tries each address in turn (a server may be reachable on several networks).
    pub async fn connect(addrs: &[SocketAddr], cfg: ClientConfig) -> Result<Client> {
        let mut last_err = anyhow!("no address to connect to");
        for addr in addrs {
            match timeout(Duration::from_secs(3), TcpStream::connect(addr)).await {
                Ok(Ok(stream)) => return Self::handshake(stream, *addr, cfg).await,
                Ok(Err(e)) => last_err = e.into(),
                Err(_) => last_err = anyhow!("timed out connecting to {addr}"),
            }
        }
        Err(last_err)
    }

    async fn handshake(stream: TcpStream, addr: SocketAddr, cfg: ClientConfig) -> Result<Client> {
        stream.set_nodelay(true)?;
        let (mut rd, mut wr) = stream.into_split();
        write_msg(&mut wr, &Msg::Hello {
            proto: PROTO_VERSION,
            id: cfg.id.clone(),
            name: cfg.name.clone(),
            platform: cfg.platform.clone(),
            pin: cfg.pin.clone(),
        }).await?;
        let (token, udp_port, format, server_id, server_name, source) =
            match timeout(Duration::from_secs(5), read_msg(&mut rd)).await?? {
                Msg::Welcome { token, udp_port, format, id, name, source, .. } => (token, udp_port, format, id, name, source),
                Msg::Error { code, message } => return Err(Refused { code, message }.into()),
                other => bail!("unexpected reply {other:?}"),
            };
        if format.codec != "pcm_s16le" {
            bail!("unsupported codec {}", format.codec);
        }

        let channels = format.channels as usize;
        let jitter = Arc::new(Mutex::new(JitterBuffer::new(CHANNELS as usize, format.sample_rate, cfg.buffer_ms)));
        let status = Arc::new(Mutex::new(ClientStatus {
            connected: true,
            server_id,
            server_name,
            source: Some(source),
            ..Default::default()
        }));
        let (stop, _) = watch::channel(false);

        let udp = Arc::new(UdpSocket::bind(("0.0.0.0", 0)).await?);
        let server_udp = SocketAddr::new(addr.ip(), udp_port);
        let mut hi = Vec::new();
        Header { kind: KIND_HI, token, seq: 0 }.write(&mut hi);

        // Keep-alive "hi" so the server knows (and the firewall keeps open) our UDP address.
        {
            let (udp, mut stop) = (udp.clone(), stop.subscribe());
            tokio::spawn(async move {
                let mut tick = interval(Duration::from_secs(1));
                loop {
                    tokio::select! {
                        _ = tick.tick() => { let _ = udp.send_to(&hi, server_udp).await; }
                        _ = stop.changed() => break,
                    }
                }
            });
        }

        // Audio receiver.
        {
            let (udp, jitter, mut stop) = (udp.clone(), jitter.clone(), stop.subscribe());
            tokio::spawn(async move {
                let mut buf = vec![0u8; 65536];
                let (mut pcm, mut stereo) = (Vec::new(), Vec::new());
                loop {
                    tokio::select! {
                        r = udp.recv_from(&mut buf) => {
                            let Ok((n, _)) = r else { continue };
                            let Some(h) = Header::parse(&buf[..n]) else { continue };
                            if h.kind != KIND_AUDIO || h.token != token { continue }
                            decode_pcm(&buf[HEADER_LEN..n], &mut pcm);
                            stereo.clear();
                            let floats: Vec<f32> = pcm.iter().map(|s| from_i16(*s)).collect();
                            crate::resample::map_channels(&floats, channels, CHANNELS as usize, &mut stereo);
                            jitter.lock().unwrap().push(h.seq, &stereo);
                        }
                        _ = stop.changed() => break,
                    }
                }
            });
        }

        // Control channel: ping/pong, format changes, goodbye.
        {
            let (status, jitter, stop_tx, mut stop) = (status.clone(), jitter.clone(), stop.clone(), stop.subscribe());
            let sample_rate = format.sample_rate;
            tokio::spawn(async move {
                let mut tick = interval(Duration::from_secs(1));
                let mut last_seen = Instant::now();
                let reason: Result<()> = async {
                    loop {
                        tokio::select! {
                            msg = read_msg(&mut rd) => {
                                last_seen = Instant::now();
                                match msg? {
                                    Msg::Ping { t } => write_msg(&mut wr, &Msg::Pong { t }).await?,
                                    Msg::Pong { t } => status.lock().unwrap().rtt_ms = now_ms().saturating_sub(t) as u32,
                                    Msg::Format(_) => jitter.lock().unwrap().reset(),
                                    Msg::Bye => return Ok(()),
                                    _ => {}
                                }
                            }
                            _ = tick.tick() => {
                                if last_seen.elapsed() > Duration::from_secs(5) {
                                    bail!("server stopped responding");
                                }
                                write_msg(&mut wr, &Msg::Ping { t: now_ms() }).await?;
                                let jb = jitter.lock().unwrap();
                                let mut st = status.lock().unwrap();
                                st.lost = jb.lost;
                                st.underruns = jb.underruns;
                                st.buffered_ms = jb.buffered_ms(sample_rate);
                            }
                            _ = stop.changed() => {
                                let _ = write_msg(&mut wr, &Msg::Bye).await;
                                return Ok(());
                            }
                        }
                    }
                }.await;
                if let Err(e) = reason {
                    log::info!("disconnected: {e}");
                }
                status.lock().unwrap().connected = false;
                let _ = stop_tx.send(true);
            });
        }

        Ok(Client { jitter, status, stop })
    }

    pub fn jitter(&self) -> Arc<Mutex<JitterBuffer>> {
        self.jitter.clone()
    }

    pub fn status(&self) -> ClientStatus {
        self.status.lock().unwrap().clone()
    }

    pub fn set_buffer_ms(&self, ms: u32) {
        self.jitter.lock().unwrap().set_target(SAMPLE_RATE, ms);
    }
}

impl Drop for Client {
    fn drop(&mut self) {
        let _ = self.stop.send(true);
    }
}
