//! Desktop audio I/O through cpal (WASAPI on Windows).
//!
//! Everything leaving or entering this module is interleaved stereo f32 at 48 kHz;
//! conversion to and from the device's own format happens here.

use anyhow::{anyhow, bail, Context, Result};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, SampleFormat, SizedSample};
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::Duration;

use crate::protocol::{CHANNELS, FRAME_SAMPLES, SAMPLE_RATE};
use crate::resample::{map_channels, Resampler};

/// Name fragment of the VB-Cable playback endpoint. Whatever is played into it shows up
/// as the "CABLE Output" microphone in other apps.
const VIRTUAL_CABLE_HINT: &str = "CABLE Input";

#[derive(Debug, Clone)]
pub enum CaptureKind {
    /// A microphone; `None` = system default.
    Mic(Option<String>),
    /// What a playback device is playing (WASAPI loopback); `None` = system default.
    Loopback(Option<String>),
}

pub fn input_devices() -> Vec<String> {
    let host = cpal::default_host();
    host.input_devices().map(|ds| ds.filter_map(|d| d.name().ok()).collect()).unwrap_or_default()
}

pub fn output_devices() -> Vec<String> {
    let host = cpal::default_host();
    host.output_devices()
        .map(|ds| ds.filter_map(|d| d.name().ok()).filter(|n| !n.contains(VIRTUAL_CABLE_HINT)).collect())
        .unwrap_or_default()
}

/// The VB-Cable input endpoint, if VB-Cable is installed.
pub fn virtual_cable() -> Option<String> {
    let host = cpal::default_host();
    host.output_devices().ok()?.filter_map(|d| d.name().ok()).find(|n| n.contains(VIRTUAL_CABLE_HINT))
}

fn find_device(input: bool, name: &Option<String>) -> Result<cpal::Device> {
    let host = cpal::default_host();
    match name {
        Some(n) => {
            let mut list = if input { host.input_devices()? } else { host.output_devices()? };
            list.find(|d| d.name().map(|x| &x == n).unwrap_or(false))
                .ok_or_else(|| anyhow!("audio device not found: {n}"))
        }
        None => if input { host.default_input_device() } else { host.default_output_device() }
            .ok_or_else(|| anyhow!("no default audio device")),
    }
}

/// Owns a running cpal stream. cpal streams can't move between threads on Windows,
/// so each one lives on its own thread until this handle is dropped.
pub struct AudioThread {
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl Drop for AudioThread {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

fn spawn_stream<F>(build: F) -> Result<AudioThread>
where
    F: FnOnce() -> Result<cpal::Stream> + Send + 'static,
{
    let stop = Arc::new(AtomicBool::new(false));
    let (ready_tx, ready_rx) = mpsc::channel::<Result<()>>();
    let stop2 = stop.clone();
    let handle = std::thread::Builder::new().name("echoport-audio".into()).spawn(move || {
        let stream = match build().and_then(|s| s.play().map(|_| s).context("starting audio stream")) {
            Ok(s) => {
                let _ = ready_tx.send(Ok(()));
                s
            }
            Err(e) => {
                let _ = ready_tx.send(Err(e));
                return;
            }
        };
        while !stop2.load(Ordering::Relaxed) {
            std::thread::sleep(Duration::from_millis(50));
        }
        drop(stream);
    })?;
    ready_rx.recv().map_err(|_| anyhow!("audio thread exited"))??;
    Ok(AudioThread { stop, handle: Some(handle) })
}

/// Starts capturing; `on_audio` gets 48 kHz stereo blocks of any length.
pub fn start_capture<F>(kind: CaptureKind, on_audio: F) -> Result<AudioThread>
where
    F: FnMut(&[f32]) + Send + 'static,
{
    spawn_stream(move || {
        let (device, config) = match &kind {
            CaptureKind::Mic(name) => {
                let d = find_device(true, name)?;
                let c = d.default_input_config()?;
                (d, c)
            }
            // Building an input stream on an output device turns on WASAPI loopback.
            CaptureKind::Loopback(name) => {
                let d = find_device(false, name)?;
                let c = d.default_output_config()?;
                (d, c)
            }
        };
        let format = config.sample_format();
        let config: cpal::StreamConfig = config.into();
        log::info!("capture {:?}: {} Hz, {} ch, {:?}", kind, config.sample_rate.0, config.channels, format);
        match format {
            SampleFormat::F32 => build_input::<f32, F>(&device, &config, on_audio),
            SampleFormat::I16 => build_input::<i16, F>(&device, &config, on_audio),
            SampleFormat::I32 => build_input::<i32, F>(&device, &config, on_audio),
            other => bail!("unsupported sample format {other:?}"),
        }
    })
}

fn build_input<T, F>(device: &cpal::Device, config: &cpal::StreamConfig, mut on_audio: F) -> Result<cpal::Stream>
where
    T: SizedSample,
    f32: FromSample<T>,
    F: FnMut(&[f32]) + Send + 'static,
{
    let dev_ch = config.channels as usize;
    let mut resampler = Resampler::new(config.sample_rate.0, SAMPLE_RATE, CHANNELS as usize);
    let (mut raw, mut stereo, mut out) = (Vec::new(), Vec::new(), Vec::new());
    let stream = device.build_input_stream(
        config,
        move |data: &[T], _| {
            raw.clear();
            raw.extend(data.iter().map(|s| s.to_sample::<f32>()));
            stereo.clear();
            map_channels(&raw, dev_ch, CHANNELS as usize, &mut stereo);
            out.clear();
            resampler.process(&stereo, &mut out);
            on_audio(&out);
        },
        |e| log::error!("capture stream error: {e}"),
        None,
    )?;
    Ok(stream)
}

/// Starts playback; `pull` must fill the slice with 48 kHz stereo audio.
pub fn start_playback<F>(device: Option<String>, pull: F) -> Result<AudioThread>
where
    F: FnMut(&mut [f32]) + Send + 'static,
{
    spawn_stream(move || {
        let d = find_device(false, &device)?;
        let config = d.default_output_config()?;
        let format = config.sample_format();
        let config: cpal::StreamConfig = config.into();
        log::info!("playback {:?}: {} Hz, {} ch, {:?}", device, config.sample_rate.0, config.channels, format);
        match format {
            SampleFormat::F32 => build_output::<f32, F>(&d, &config, pull),
            SampleFormat::I16 => build_output::<i16, F>(&d, &config, pull),
            SampleFormat::I32 => build_output::<i32, F>(&d, &config, pull),
            other => bail!("unsupported sample format {other:?}"),
        }
    })
}

fn build_output<T, F>(device: &cpal::Device, config: &cpal::StreamConfig, mut pull: F) -> Result<cpal::Stream>
where
    T: SizedSample + FromSample<f32>,
    F: FnMut(&mut [f32]) + Send + 'static,
{
    let dev_ch = config.channels as usize;
    let mut resampler = Resampler::new(SAMPLE_RATE, config.sample_rate.0, CHANNELS as usize);
    let mut block = vec![0f32; FRAME_SAMPLES * CHANNELS as usize];
    let (mut resampled, mut mapped) = (Vec::new(), Vec::new());
    let mut fifo: VecDeque<f32> = VecDeque::new();
    let stream = device.build_output_stream(
        config,
        move |data: &mut [T], _| {
            while fifo.len() < data.len() {
                pull(&mut block);
                resampled.clear();
                resampler.process(&block, &mut resampled);
                mapped.clear();
                map_channels(&resampled, CHANNELS as usize, dev_ch, &mut mapped);
                fifo.extend(mapped.iter().copied());
            }
            for s in data.iter_mut() {
                *s = T::from_sample(fifo.pop_front().unwrap_or(0.0));
            }
        },
        |e| log::error!("playback stream error: {e}"),
        None,
    )?;
    Ok(stream)
}
