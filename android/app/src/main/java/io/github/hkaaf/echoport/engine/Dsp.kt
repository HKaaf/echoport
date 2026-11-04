package io.github.hkaaf.echoport.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

enum class EqPreset { FLAT, VOICE_CLARITY, BASS_BOOST, TREBLE }

/** RBJ cookbook biquad, same filters as the desktop app (core/src/dsp.rs). */
private class Biquad(private val b0: Float, private val b1: Float, private val b2: Float, private val a1: Float, private val a2: Float) {
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    fun run(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    companion object {
        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

        private fun w(freq: Double) = 2 * Math.PI * freq / Proto.SAMPLE_RATE

        fun highPass(freq: Double, q: Double): Biquad {
            val c = cos(w(freq)); val alpha = sin(w(freq)) / (2 * q)
            return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun peak(freq: Double, q: Double, db: Double): Biquad {
            val a = 10.0.pow(db / 40); val c = cos(w(freq)); val alpha = sin(w(freq)) / (2 * q)
            return make(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        }

        fun shelf(freq: Double, db: Double, low: Boolean): Biquad {
            val a = 10.0.pow(db / 40); val c = cos(w(freq)); val alpha = sin(w(freq)) / (2 * 0.707)
            val sq = 2 * sqrt(a) * alpha; val s = if (low) 1.0 else -1.0
            return make(
                a * ((a + 1) - s * (a - 1) * c + sq),
                s * 2 * a * ((a - 1) - s * (a + 1) * c),
                a * ((a + 1) - s * (a - 1) * c - sq),
                (a + 1) + s * (a - 1) * c + sq,
                -s * 2 * ((a - 1) + s * (a + 1) * c),
                (a + 1) + s * (a - 1) * c - sq,
            )
        }
    }
}

private fun filters(p: EqPreset): List<Biquad> = when (p) {
    EqPreset.FLAT -> emptyList()
    EqPreset.VOICE_CLARITY -> listOf(Biquad.highPass(90.0, 0.707), Biquad.peak(3000.0, 1.0, 4.0))
    EqPreset.BASS_BOOST -> listOf(Biquad.shelf(120.0, 6.0, low = true))
    EqPreset.TREBLE -> listOf(Biquad.shelf(6000.0, 5.0, low = false))
}

/** Gain + EQ on interleaved stereo floats (-1..1). */
class EffectChain(gainPercent: Int, eq: EqPreset) {
    @Volatile var gainPercent = gainPercent
    var eq = eq
        set(value) {
            if (value != field) { field = value; chains = arrayOf(filters(value), filters(value)) }
        }
    private var chains = arrayOf(filters(eq), filters(eq))

    fun process(buf: FloatArray) {
        val gain = gainPercent / 100f
        val ch = chains
        for (i in buf.indices) {
            var x = buf[i]
            for (f in ch[i and 1]) x = f.run(x)
            buf[i] = softClip(x * gain)
        }
    }
}

fun softClip(x: Float): Float {
    val a = abs(x)
    return if (a <= 0.9f) x else sign(x) * (0.9f + 0.1f * tanh((a - 0.9f) / 0.1f))
}

fun peakDb(buf: FloatArray): Float {
    var peak = 0f
    for (s in buf) peak = maxOf(peak, abs(s))
    return if (peak <= 1e-6f) -96f else 20f * log10(peak)
}
