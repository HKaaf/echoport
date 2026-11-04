package io.github.hkaaf.echoport.engine

/**
 * Player-side jitter buffer over interleaved stereo 16-bit samples.
 * Mirrors core/src/jitter.rs on the desktop.
 */
class JitterBuffer(targetMs: Int) {
    private val ring = ShortArray(Proto.SAMPLE_RATE * Proto.CHANNELS * 2) // 2 seconds
    private var read = 0
    private var size = 0
    private var target = 0
    private var lastSeq: Int? = null
    private var filling = true
    var lost = 0L; private set
    var underruns = 0L; private set

    init { setTarget(targetMs) }

    @Synchronized fun setTarget(ms: Int) {
        target = Proto.SAMPLE_RATE * ms / 1000 * Proto.CHANNELS
    }

    @Synchronized fun reset() {
        read = 0; size = 0; lastSeq = null; filling = true
    }

    @Synchronized fun push(seq: Int, samples: ShortArray, count: Int) {
        lastSeq?.let { last ->
            val delta = (seq - last).toLong() and 0xFFFFFFFFL
            if (delta == 0L || delta > 0x7FFFFFFFL) return // duplicate or late
            lost += delta - 1
        }
        lastSeq = seq
        for (i in 0 until count) {
            if (size == ring.size) { read = (read + 1) % ring.size; size-- }
            ring[(read + size) % ring.size] = samples[i]
            size++
        }
        // Too far ahead after a stall: drop old audio so latency stays bounded.
        val max = target * 2 + Proto.CHANNELS * 480
        if (size > max) {
            var drop = size - target
            drop -= drop % Proto.CHANNELS
            read = (read + drop) % ring.size
            size -= drop
        }
    }

    @Synchronized fun pop(out: ShortArray) {
        if (filling) {
            if (size >= maxOf(target, Proto.CHANNELS)) filling = false else { out.fill(0); return }
        }
        for (i in out.indices) {
            if (size == 0) {
                out[i] = 0
                if (!filling) { underruns++; filling = true }
            } else {
                out[i] = ring[read]
                read = (read + 1) % ring.size
                size--
            }
        }
    }

    @Synchronized fun bufferedMs(): Int = size / Proto.CHANNELS * 1000 / Proto.SAMPLE_RATE
}
