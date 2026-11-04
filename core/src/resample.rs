//! Streaming linear resampler and channel mapping. Linear interpolation is plenty for
//! voice and the common 44.1 kHz <-> 48 kHz case, and adds no delay.

pub struct Resampler {
    channels: usize,
    step: f64,
    pos: f64,
    prev: Vec<f32>,
}

impl Resampler {
    pub fn new(from_rate: u32, to_rate: u32, channels: usize) -> Self {
        Resampler { channels, step: from_rate as f64 / to_rate as f64, pos: 0.0, prev: vec![0.0; channels] }
    }

    pub fn is_passthrough(&self) -> bool {
        self.step == 1.0
    }

    /// Appends resampled interleaved audio from `input` to `out`.
    pub fn process(&mut self, input: &[f32], out: &mut Vec<f32>) {
        let ch = self.channels;
        if self.is_passthrough() {
            out.extend_from_slice(input);
            return;
        }
        let frames = input.len() / ch;
        // Virtual index 0 is the last frame of the previous call; index k is input frame k-1.
        let get = |k: usize, c: usize, prev: &[f32]| if k == 0 { prev[c] } else { input[(k - 1) * ch + c] };
        while self.pos < frames as f64 {
            let k = self.pos.floor() as usize;
            let t = (self.pos - k as f64) as f32;
            for c in 0..ch {
                let a = get(k, c, &self.prev);
                let b = get(k + 1, c, &self.prev);
                out.push(a + (b - a) * t);
            }
            self.pos += self.step;
        }
        self.pos -= frames as f64;
        if frames > 0 {
            self.prev.copy_from_slice(&input[(frames - 1) * ch..frames * ch]);
        }
    }
}

/// Converts interleaved audio between channel counts (mono <-> stereo, or drops extras).
pub fn map_channels(input: &[f32], from: usize, to: usize, out: &mut Vec<f32>) {
    if from == to {
        out.extend_from_slice(input);
        return;
    }
    for frame in input.chunks_exact(from) {
        for c in 0..to {
            let v = if from == 1 {
                frame[0]
            } else if to == 1 {
                (frame[0] + frame[1]) * 0.5
            } else {
                frame.get(c).copied().unwrap_or(0.0)
            };
            out.push(v);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn output_length_tracks_rate_ratio() {
        let mut r = Resampler::new(44_100, 48_000, 1);
        let input = vec![0.5f32; 4410];
        let mut out = Vec::new();
        for _ in 0..10 {
            r.process(&input, &mut out);
        }
        let expected = 48_000.0;
        assert!((out.len() as f64 - expected).abs() < 10.0, "got {}", out.len());
    }

    #[test]
    fn mono_to_stereo() {
        let mut out = Vec::new();
        map_channels(&[1.0, 2.0], 1, 2, &mut out);
        assert_eq!(out, vec![1.0, 1.0, 2.0, 2.0]);
    }
}
