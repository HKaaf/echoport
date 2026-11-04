//! Echoport core: the wire protocol, audio processing and the server/player engines.
//!
//! The desktop app is a thin UI over [`engine::Engine`]. The Android app speaks the
//! same protocol (see `PROTOCOL.md`) from Kotlin.

pub mod audio;
pub mod client;
pub mod discovery;
pub mod dsp;
pub mod engine;
pub mod jitter;
pub mod netinfo;
pub mod protocol;
pub mod resample;
pub mod server;
