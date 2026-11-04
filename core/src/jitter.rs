//! Player-side jitter buffer: absorbs network timing wobble at the cost of a fixed delay.

use std::collections::VecDeque;

pub struct JitterBuffer {
    samples: VecDeque<f32>,
    channels: usize,
    /// Target fill, in samples (all channels).
    target: usize,
    last_seq: Option<u32>,
    filling: bool,
    pub lost: u64,
    pub underruns: u64,
}

impl JitterBuffer {
    pub fn new(channels: usize, sample_rate: u32, target_ms: u32) -> Self {
        let mut jb = JitterBuffer {
            samples: VecDeque::new(),
            channels,
            target: 0,
            last_seq: None,
            filling: true,
            lost: 0,
            underruns: 0,
        };
        jb.set_target(sample_rate, target_ms);
        jb
    }

    pub fn set_target(&mut self, sample_rate: u32, target_ms: u32) {
        self.target = (sample_rate as usize * target_ms as usize / 1000) * self.channels;
    }

    pub fn reset(&mut self) {
        self.samples.clear();
        self.last_seq = None;
        self.filling = true;
    }

    pub fn push(&mut self, seq: u32, frame: &[f32]) {
        if let Some(last) = self.last_seq {
            let delta = seq.wrapping_sub(last);
            if delta == 0 || delta > u32::MAX / 2 {
                return; // duplicate or late
            }
            self.lost += (delta - 1) as u64;
        }
        self.last_seq = Some(seq);
        self.samples.extend(frame.iter().copied());
        // Too far ahead (e.g. after a Wi-Fi stall): drop the oldest audio to keep latency bounded.
        let max = self.target * 2 + self.channels * 480;
        if self.samples.len() > max {
            let excess = self.samples.len() - self.target;
            self.samples.drain(..excess - excess % self.channels);
        }
    }

    /// Fills `out` (interleaved); plays silence while (re)buffering.
    pub fn pop(&mut self, out: &mut [f32]) {
        if self.filling {
            if self.samples.len() >= self.target.max(self.channels) {
                self.filling = false;
            } else {
                out.fill(0.0);
                return;
            }
        }
        for s in out.iter_mut() {
            match self.samples.pop_front() {
                Some(v) => *s = v,
                None => {
                    *s = 0.0;
                    if !self.filling {
                        self.underruns += 1;
                        self.filling = true;
                    }
                }
            }
        }
    }

    pub fn buffered_ms(&self, sample_rate: u32) -> u32 {
        (self.samples.len() / self.channels.max(1) * 1000 / sample_rate as usize) as u32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn waits_for_target_then_plays_in_order() {
        let mut jb = JitterBuffer::new(1, 1000, 3); // 3 samples
        let mut out = [9.0; 2];
        jb.push(0, &[1.0, 2.0]);
        jb.pop(&mut out);
        assert_eq!(out, [0.0, 0.0]);
        jb.push(1, &[3.0, 4.0]);
        jb.pop(&mut out);
        assert_eq!(out, [1.0, 2.0]);
    }

    #[test]
    fn counts_lost_and_drops_late() {
        let mut jb = JitterBuffer::new(1, 1000, 1);
        jb.push(5, &[1.0]);
        jb.push(8, &[2.0]);
        jb.push(7, &[3.0]);
        assert_eq!(jb.lost, 2);
        assert_eq!(jb.samples.len(), 2);
    }
}
