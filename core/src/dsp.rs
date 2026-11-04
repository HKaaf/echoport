//! Effects applied on the server before audio is sent: noise suppression, EQ and gain.
//! All processing is on interleaved stereo f32 in the range -1.0..=1.0 at 48 kHz.

use nnnoiseless::DenoiseState;
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::f32::consts::PI;

use crate::protocol::SAMPLE_RATE;

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub enum EqPreset {
    #[default]
    Flat,
    VoiceClarity,
    BassBoost,
    Treble,
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct EffectSettings {
    /// 100 = unchanged, 400 = four times louder.
    pub gain_percent: u32,
    pub noise_suppression: bool,
    pub eq: EqPreset,
}

impl Default for EffectSettings {
    fn default() -> Self {
        Self { gain_percent: 100, noise_suppression: false, eq: EqPreset::Flat }
    }
}

/// RBJ cookbook biquad, direct form I.
#[derive(Clone, Copy, Default)]
struct Biquad {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
    x1: f32,
    x2: f32,
    y1: f32,
    y2: f32,
}

enum Shape {
    HighPass,
    Peak(f32),
    LowShelf(f32),
    HighShelf(f32),
}

impl Biquad {
    fn new(shape: Shape, freq: f32, q: f32) -> Self {
        let w = 2.0 * PI * freq / SAMPLE_RATE as f32;
        let (sin, cos) = w.sin_cos();
        let alpha = sin / (2.0 * q);
        let (b0, b1, b2, a0, a1, a2) = match shape {
            Shape::HighPass => (
                (1.0 + cos) / 2.0,
                -(1.0 + cos),
                (1.0 + cos) / 2.0,
                1.0 + alpha,
                -2.0 * cos,
                1.0 - alpha,
            ),
            Shape::Peak(db) => {
                let a = 10f32.powf(db / 40.0);
                (1.0 + alpha * a, -2.0 * cos, 1.0 - alpha * a, 1.0 + alpha / a, -2.0 * cos, 1.0 - alpha / a)
            }
            Shape::LowShelf(db) | Shape::HighShelf(db) => {
                let a = 10f32.powf(db / 40.0);
                let sq = 2.0 * a.sqrt() * alpha;
                let s = if matches!(shape, Shape::LowShelf(_)) { 1.0 } else { -1.0 };
                (
                    a * ((a + 1.0) - s * (a - 1.0) * cos + sq),
                    s * 2.0 * a * ((a - 1.0) - s * (a + 1.0) * cos),
                    a * ((a + 1.0) - s * (a - 1.0) * cos - sq),
                    (a + 1.0) + s * (a - 1.0) * cos + sq,
                    -s * 2.0 * ((a - 1.0) + s * (a + 1.0) * cos),
                    (a + 1.0) + s * (a - 1.0) * cos - sq,
                )
            }
        };
        Biquad { b0: b0 / a0, b1: b1 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0, ..Default::default() }
    }

    fn run(&mut self, x: f32) -> f32 {
        let y = self.b0 * x + self.b1 * self.x1 + self.b2 * self.x2 - self.a1 * self.y1 - self.a2 * self.y2;
        self.x2 = self.x1;
        self.x1 = x;
        self.y2 = self.y1;
        self.y1 = y;
        y
    }
}

fn eq_filters(preset: EqPreset) -> Vec<Biquad> {
    match preset {
        EqPreset::Flat => vec![],
        EqPreset::VoiceClarity => vec![
            Biquad::new(Shape::HighPass, 90.0, 0.707),
            Biquad::new(Shape::Peak(4.0), 3000.0, 1.0),
        ],
        EqPreset::BassBoost => vec![Biquad::new(Shape::LowShelf(6.0), 120.0, 0.707)],
        EqPreset::Treble => vec![Biquad::new(Shape::HighShelf(5.0), 6000.0, 0.707)],
    }
}

/// RNNoise on one channel. It works on 480-sample blocks, so it adds 10 ms of delay.
struct Denoiser {
    state: Box<DenoiseState<'static>>,
    input: Vec<f32>,
    output: VecDeque<f32>,
}

impl Denoiser {
    fn new() -> Self {
        Denoiser {
            state: DenoiseState::new(),
            input: Vec::with_capacity(DenoiseState::FRAME_SIZE),
            output: std::iter::repeat(0.0).take(DenoiseState::FRAME_SIZE).collect(),
        }
    }

    fn run(&mut self, x: f32) -> f32 {
        // RNNoise expects 16-bit scale.
        self.input.push(x * 32768.0);
        if self.input.len() == DenoiseState::FRAME_SIZE {
            let mut out = [0f32; DenoiseState::FRAME_SIZE];
            self.state.process_frame(&mut out, &self.input);
            self.output.extend(out.iter().map(|s| s / 32768.0));
            self.input.clear();
        }
        self.output.pop_front().unwrap_or(0.0)
    }
}

pub struct EffectChain {
    settings: EffectSettings,
    eq: [Vec<Biquad>; 2],
    denoise: Option<[Denoiser; 2]>,
}

impl EffectChain {
    pub fn new(settings: EffectSettings) -> Self {
        let mut chain = EffectChain { settings, eq: [vec![], vec![]], denoise: None };
        chain.update(settings);
        chain
    }

    pub fn update(&mut self, settings: EffectSettings) {
        if settings.eq != self.settings.eq || self.eq[0].is_empty() {
            self.eq = [eq_filters(settings.eq), eq_filters(settings.eq)];
        }
        if settings.noise_suppression && self.denoise.is_none() {
            self.denoise = Some([Denoiser::new(), Denoiser::new()]);
        } else if !settings.noise_suppression {
            self.denoise = None;
        }
        self.settings = settings;
    }

    /// Processes interleaved stereo in place.
    pub fn process(&mut self, buf: &mut [f32]) {
        let gain = self.settings.gain_percent as f32 / 100.0;
        for frame in buf.chunks_exact_mut(2) {
            for (ch, s) in frame.iter_mut().enumerate() {
                let mut x = *s;
                if let Some(d) = self.denoise.as_mut() {
                    x = d[ch].run(x);
                }
                for f in self.eq[ch].iter_mut() {
                    x = f.run(x);
                }
                *s = soft_clip(x * gain);
            }
        }
    }
}

/// Keeps boosted audio from hard-clipping: linear below 0.9, smooth above.
fn soft_clip(x: f32) -> f32 {
    let a = x.abs();
    if a <= 0.9 {
        x
    } else {
        x.signum() * (0.9 + 0.1 * ((a - 0.9) / 0.1).tanh())
    }
}

pub fn to_i16(x: f32) -> i16 {
    (x.clamp(-1.0, 1.0) * 32767.0) as i16
}

pub fn from_i16(x: i16) -> f32 {
    x as f32 / 32768.0
}

/// Peak level of a block in dBFS, for the level meter.
pub fn peak_db(buf: &[f32]) -> f32 {
    let peak = buf.iter().fold(0f32, |m, s| m.max(s.abs()));
    if peak <= 1e-6 {
        -96.0
    } else {
        20.0 * peak.log10()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gain_doubles_quiet_signal() {
        let mut c = EffectChain::new(EffectSettings { gain_percent: 200, ..Default::default() });
        let mut buf = [0.1, -0.1];
        c.process(&mut buf);
        assert!((buf[0] - 0.2).abs() < 1e-6 && (buf[1] + 0.2).abs() < 1e-6);
    }

    #[test]
    fn boost_never_exceeds_full_scale() {
        let mut c = EffectChain::new(EffectSettings { gain_percent: 400, ..Default::default() });
        let mut buf = [0.9, -0.9];
        c.process(&mut buf);
        assert!(buf.iter().all(|s| s.abs() <= 1.0));
    }
}
