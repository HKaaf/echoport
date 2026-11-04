//! Wire format. Keep in sync with `PROTOCOL.md`.

use anyhow::{bail, Result};
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

pub const PROTO_VERSION: u32 = 1;
pub const DEFAULT_PORT: u16 = 47210;
pub const SERVICE_TYPE: &str = "_echoport._tcp.local.";

pub const SAMPLE_RATE: u32 = 48_000;
pub const CHANNELS: u16 = 2;
pub const FRAME_MS: u32 = 5;
/// Samples per channel in one frame.
pub const FRAME_SAMPLES: usize = (SAMPLE_RATE * FRAME_MS / 1000) as usize;

const MAX_MSG: usize = 64 * 1024;

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Source {
    Mic,
    System,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AudioFormat {
    pub sample_rate: u32,
    pub channels: u16,
    pub frame_ms: u32,
    pub codec: String,
}

impl Default for AudioFormat {
    fn default() -> Self {
        Self {
            sample_rate: SAMPLE_RATE,
            channels: CHANNELS,
            frame_ms: FRAME_MS,
            codec: "pcm_s16le".into(),
        }
    }
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(tag = "type", rename_all = "lowercase", rename_all_fields = "camelCase")]
pub enum Msg {
    Hello {
        proto: u32,
        id: String,
        name: String,
        platform: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        pin: Option<String>,
    },
    Welcome {
        proto: u32,
        id: String,
        name: String,
        source: Source,
        token: u32,
        udp_port: u16,
        #[serde(flatten)]
        format: AudioFormat,
    },
    Format(AudioFormat),
    Error {
        code: String,
        message: String,
    },
    Ping {
        t: u64,
    },
    Pong {
        t: u64,
    },
    Bye,
}

pub async fn read_msg<R: AsyncReadExt + Unpin>(r: &mut R) -> Result<Msg> {
    let len = r.read_u32().await? as usize;
    if len > MAX_MSG {
        bail!("control message too large ({len} bytes)");
    }
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf).await?;
    Ok(serde_json::from_slice(&buf)?)
}

pub async fn write_msg<W: AsyncWriteExt + Unpin>(w: &mut W, msg: &Msg) -> Result<()> {
    let body = serde_json::to_vec(msg)?;
    w.write_u32(body.len() as u32).await?;
    w.write_all(&body).await?;
    w.flush().await?;
    Ok(())
}

// ---- UDP packets ----

pub const HEADER_LEN: usize = 12;
pub const KIND_AUDIO: u8 = 0;
pub const KIND_HI: u8 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Header {
    pub kind: u8,
    pub token: u32,
    pub seq: u32,
}

impl Header {
    pub fn write(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(b"EP");
        out.push(PROTO_VERSION as u8);
        out.push(self.kind);
        out.extend_from_slice(&self.token.to_be_bytes());
        out.extend_from_slice(&self.seq.to_be_bytes());
    }

    pub fn parse(buf: &[u8]) -> Option<Header> {
        if buf.len() < HEADER_LEN || &buf[0..2] != b"EP" || buf[2] != PROTO_VERSION as u8 {
            return None;
        }
        Some(Header {
            kind: buf[3],
            token: u32::from_be_bytes(buf[4..8].try_into().ok()?),
            seq: u32::from_be_bytes(buf[8..12].try_into().ok()?),
        })
    }
}

pub fn encode_audio(token: u32, seq: u32, samples: &[i16], out: &mut Vec<u8>) {
    out.clear();
    Header { kind: KIND_AUDIO, token, seq }.write(out);
    for s in samples {
        out.extend_from_slice(&s.to_le_bytes());
    }
}

pub fn decode_pcm(payload: &[u8], out: &mut Vec<i16>) {
    out.clear();
    out.extend(payload.chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])));
}

pub fn now_ms() -> u64 {
    use std::sync::OnceLock;
    use std::time::Instant;
    static START: OnceLock<Instant> = OnceLock::new();
    START.get_or_init(Instant::now).elapsed().as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn welcome_json_matches_spec() {
        let m = Msg::Welcome {
            proto: 1,
            id: "a".into(),
            name: "PC".into(),
            source: Source::System,
            token: 7,
            udp_port: 47210,
            format: AudioFormat::default(),
        };
        let v: serde_json::Value = serde_json::to_value(&m).unwrap();
        assert_eq!(v["type"], "welcome");
        assert_eq!(v["udpPort"], 47210);
        assert_eq!(v["sampleRate"], 48000);
        assert_eq!(v["source"], "system");
        let back: Msg = serde_json::from_value(v).unwrap();
        assert!(matches!(back, Msg::Welcome { token: 7, .. }));
    }

    #[test]
    fn header_roundtrip() {
        let mut buf = Vec::new();
        encode_audio(42, 9, &[1, -2], &mut buf);
        let h = Header::parse(&buf).unwrap();
        assert_eq!(h, Header { kind: KIND_AUDIO, token: 42, seq: 9 });
        let mut pcm = Vec::new();
        decode_pcm(&buf[HEADER_LEN..], &mut pcm);
        assert_eq!(pcm, vec![1, -2]);
    }
}
