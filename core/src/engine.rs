//! Ties capture, effects, networking and playback together. The desktop UI drives one
//! [`Engine`]; it can run a server and a player at the same time.

use anyhow::{bail, Result};
use serde::{Deserialize, Serialize};
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::mpsc::{sync_channel, SyncSender};
use std::sync::{Arc, Mutex};

use crate::audio::{self, AudioThread, CaptureKind};
use crate::client::{Client, ClientConfig, ClientStatus};
use crate::discovery::{Advertiser, Browser, DiscoveredServer};
use crate::dsp::{peak_db, to_i16, EffectChain, EffectSettings};
use crate::protocol::{Source, CHANNELS, FRAME_SAMPLES};
use crate::server::{PeerInfo, Server, ServerConfig};

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ServerOptions {
    pub source: Source,
    /// Mic name for `Mic`, playback device name for `System`; `None` = default.
    pub device: Option<String>,
    pub port: u16,
    pub pin: Option<String>,
    pub effects: EffectSettings,
}

#[derive(Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum Destination {
    Speaker,
    VirtualMic,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct PlayerOptions {
    pub addrs: Vec<String>,
    pub port: u16,
    pub pin: Option<String>,
    pub destination: Destination,
    /// Speaker device name; `None` = default. Ignored for the virtual mic.
    pub device: Option<String>,
    pub volume_percent: u32,
    pub buffer_ms: u32,
}

#[derive(Serialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct EngineStatus {
    pub server_running: bool,
    pub server_level_db: f32,
    pub peers: Vec<PeerInfo>,
    pub player: Option<ClientStatus>,
    pub recording: bool,
}

struct ServerRun {
    server: Server,
    effects: Arc<Mutex<EffectChain>>,
    level: Arc<AtomicU32>,
    _capture: AudioThread,
    _advertiser: Option<Advertiser>,
}

struct PlayerRun {
    client: Client,
    volume: Arc<AtomicU32>,
    recorder: Arc<Mutex<Option<SyncSender<Vec<f32>>>>>,
    _playback: AudioThread,
}

pub struct Engine {
    id: String,
    name: String,
    server: Option<ServerRun>,
    player: Option<PlayerRun>,
    browser: Option<Browser>,
}

impl Engine {
    pub fn new(id: String, name: String) -> Self {
        let browser = Browser::start(id.clone()).map_err(|e| log::warn!("mDNS browse failed: {e}")).ok();
        Engine { id, name, server: None, player: None, browser }
    }

    pub fn set_name(&mut self, name: String) {
        self.name = name;
    }

    pub fn discovered(&self) -> Vec<DiscoveredServer> {
        self.browser.as_ref().map(|b| b.servers()).unwrap_or_default()
    }

    // ---- server ----

    pub async fn start_server(&mut self, opts: ServerOptions) -> Result<()> {
        self.server = None;
        let server = Server::start(ServerConfig {
            id: self.id.clone(),
            name: self.name.clone(),
            port: opts.port,
            pin: opts.pin.clone(),
            source: opts.source,
        }).await?;

        let frames = server.frame_sender();
        let effects = Arc::new(Mutex::new(EffectChain::new(opts.effects)));
        let level = Arc::new(AtomicU32::new((-96f32).to_bits()));
        let kind = match opts.source {
            Source::Mic => CaptureKind::Mic(opts.device.clone()),
            Source::System => CaptureKind::Loopback(opts.device.clone()),
        };
        let frame_len = FRAME_SAMPLES * CHANNELS as usize;
        let (fx, lvl) = (effects.clone(), level.clone());
        let mut pending: Vec<f32> = Vec::new();
        let capture = audio::start_capture(kind, move |chunk| {
            pending.extend_from_slice(chunk);
            while pending.len() >= frame_len {
                let mut frame: Vec<f32> = pending.drain(..frame_len).collect();
                fx.lock().unwrap().process(&mut frame);
                lvl.store(peak_db(&frame).to_bits(), Ordering::Relaxed);
                let pcm: Vec<i16> = frame.iter().map(|s| to_i16(*s)).collect();
                let _ = frames.send(Arc::new(pcm));
            }
        })?;

        let advertiser = Advertiser::start(&self.id, &self.name, opts.port, opts.source, opts.pin.is_some())
            .map_err(|e| log::warn!("mDNS advertise failed: {e}"))
            .ok();
        self.server = Some(ServerRun { server, effects, level, _capture: capture, _advertiser: advertiser });
        Ok(())
    }

    pub fn stop_server(&mut self) {
        self.server = None;
    }

    pub fn set_effects(&self, effects: EffectSettings) {
        if let Some(s) = &self.server {
            s.effects.lock().unwrap().update(effects);
        }
    }

    pub fn kick(&self, peer_id: &str) {
        if let Some(s) = &self.server {
            s.server.kick(peer_id);
        }
    }

    // ---- player ----

    pub async fn connect(&mut self, opts: PlayerOptions) -> Result<()> {
        self.player = None;
        let addrs: Vec<SocketAddr> = opts
            .addrs
            .iter()
            .filter_map(|a| a.trim().parse::<IpAddr>().ok())
            .map(|ip| SocketAddr::new(ip, opts.port))
            .collect();
        if addrs.is_empty() {
            bail!("invalid IP address");
        }
        let device = match opts.destination {
            Destination::Speaker => opts.device.clone(),
            Destination::VirtualMic => match audio::virtual_cable() {
                Some(d) => Some(d),
                None => bail!("vbcable_missing"),
            },
        };
        let client = Client::connect(&addrs, ClientConfig {
            id: self.id.clone(),
            name: self.name.clone(),
            platform: std::env::consts::OS.into(),
            pin: opts.pin.clone(),
            buffer_ms: opts.buffer_ms,
        }).await?;

        let jitter = client.jitter();
        let volume = Arc::new(AtomicU32::new(opts.volume_percent));
        let recorder: Arc<Mutex<Option<SyncSender<Vec<f32>>>>> = Default::default();
        let (vol, rec) = (volume.clone(), recorder.clone());
        let playback = audio::start_playback(device, move |out| {
            jitter.lock().unwrap().pop(out);
            let gain = vol.load(Ordering::Relaxed) as f32 / 100.0;
            if gain != 1.0 {
                out.iter_mut().for_each(|s| *s = (*s * gain).clamp(-1.0, 1.0));
            }
            if let Some(tx) = rec.lock().unwrap().as_ref() {
                let _ = tx.try_send(out.to_vec());
            }
        })?;
        self.player = Some(PlayerRun { client, volume, recorder, _playback: playback });
        Ok(())
    }

    pub fn disconnect(&mut self) {
        self.player = None;
    }

    pub fn set_volume(&self, percent: u32) {
        if let Some(p) = &self.player {
            p.volume.store(percent, Ordering::Relaxed);
        }
    }

    pub fn set_buffer(&self, ms: u32) {
        if let Some(p) = &self.player {
            p.client.set_buffer_ms(ms);
        }
    }

    /// Records what the player is playing to a 16-bit WAV file.
    pub fn start_recording(&self, path: PathBuf) -> Result<()> {
        let Some(p) = &self.player else { bail!("not connected") };
        let spec = hound::WavSpec {
            channels: CHANNELS,
            sample_rate: crate::protocol::SAMPLE_RATE,
            bits_per_sample: 16,
            sample_format: hound::SampleFormat::Int,
        };
        let mut writer = hound::WavWriter::create(&path, spec)?;
        let (tx, rx) = sync_channel::<Vec<f32>>(256);
        std::thread::Builder::new().name("echoport-record".into()).spawn(move || {
            while let Ok(block) = rx.recv() {
                for s in block {
                    if writer.write_sample(to_i16(s)).is_err() {
                        return;
                    }
                }
            }
            let _ = writer.finalize();
        })?;
        *p.recorder.lock().unwrap() = Some(tx);
        Ok(())
    }

    pub fn stop_recording(&self) {
        if let Some(p) = &self.player {
            *p.recorder.lock().unwrap() = None;
        }
    }

    pub fn status(&mut self) -> EngineStatus {
        // Drop a player whose server went away so the UI shows "disconnected" once.
        let player = self.player.as_ref().map(|p| p.client.status());
        if matches!(&player, Some(s) if !s.connected) {
            self.player = None;
        }
        EngineStatus {
            server_running: self.server.is_some(),
            server_level_db: self.server.as_ref().map(|s| f32::from_bits(s.level.load(Ordering::Relaxed))).unwrap_or(-96.0),
            peers: self.server.as_ref().map(|s| s.server.peers()).unwrap_or_default(),
            recording: self.player.as_ref().map(|p| p.recorder.lock().unwrap().is_some()).unwrap_or(false),
            player,
        }
    }
}
