package io.github.hkaaf.echoport.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import io.github.hkaaf.echoport.engine.ServerEngine.Companion.thread
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

data class PlayerConfig(
    val id: String,
    val name: String,
    val pin: String?,
    val bufferMs: Int,
    val volumePercent: Int,
)

data class PlayerStatus(
    val connected: Boolean,
    val serverId: String,
    val serverName: String,
    val source: String,
    val rttMs: Int,
    val lost: Long,
    val bufferedMs: Int,
)

/** Connects to a server and plays its audio through the speaker / headphones. */
class PlayerEngine(private val cfg: PlayerConfig, private val onClosed: () -> Unit) {
    private lateinit var socket: Socket
    private lateinit var out: DataOutputStream
    private lateinit var input: DataInputStream
    private lateinit var udp: DatagramSocket
    private var track: AudioTrack? = null
    private val jitter = JitterBuffer(cfg.bufferMs)
    @Volatile private var running = false
    @Volatile var volumePercent = cfg.volumePercent
    @Volatile private var rttMs = 0
    private var token = 0
    private var channels = 2
    var serverId = ""; private set
    var serverName = ""; private set
    var source = ""; private set

    /** Blocking; call off the main thread. Tries each address until one answers. */
    fun connect(addrs: List<String>, port: Int) {
        var last: Exception = IOException("no address")
        for (ip in addrs) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), 3000)
                socket = s
                handshake(ip)
                return
            } catch (e: RefusedException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last
    }

    private fun handshake(ip: String) {
        socket.tcpNoDelay = true
        socket.soTimeout = 5000
        input = DataInputStream(socket.getInputStream().buffered())
        out = DataOutputStream(socket.getOutputStream().buffered())
        Proto.writeMsg(out, Proto.msg("hello", "proto" to Proto.VERSION, "id" to cfg.id, "name" to cfg.name,
            "platform" to "android", "pin" to cfg.pin))
        val reply = Proto.readMsg(input)
        when (reply.optString("type")) {
            "welcome" -> {}
            "error" -> { socket.close(); throw RefusedException(reply.optString("code"), reply.optString("message")) }
            else -> throw IOException("unexpected reply")
        }
        if (reply.optString("codec") != "pcm_s16le") throw IOException("unsupported codec")
        token = reply.optLong("token").toInt()
        channels = reply.optInt("channels", 2)
        serverId = reply.optString("id")
        serverName = reply.optString("name")
        source = reply.optString("source")
        val udpPort = reply.optInt("udpPort", socket.port)

        udp = DatagramSocket()
        track = buildTrack()
        running = true
        val server = InetSocketAddress(ip, udpPort)
        thread("hi") { hiLoop(server) }
        thread("recv") { receiveLoop() }
        thread("play") { playLoop() }
        thread("control") { controlLoop() }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { Proto.writeMsg(out, Proto.msg("bye")) }
        runCatching { socket.close() }
        runCatching { udp.close() }
    }

    fun setBuffer(ms: Int) = jitter.setTarget(ms)

    fun status() = PlayerStatus(running, serverId, serverName, source, rttMs, jitter.lost, jitter.bufferedMs())

    private fun buildTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(Proto.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(Proto.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(minBuf)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun hiLoop(server: InetSocketAddress) {
        val hi = ByteArray(Proto.HEADER_LEN)
        Proto.writeHeader(hi, Proto.KIND_HI, token, 0)
        while (running) {
            runCatching { udp.send(DatagramPacket(hi, hi.size, server)) }
            Thread.sleep(1000)
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(65536)
        val samples = ShortArray(32768)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            if (runCatching { udp.receive(p) }.isFailure) continue
            val h = Proto.parseHeader(buf, p.length) ?: continue
            if (h.kind != Proto.KIND_AUDIO || h.token != token) continue
            val n = (p.length - Proto.HEADER_LEN) / 2
            var count = 0
            for (i in 0 until n) {
                val lo = buf[Proto.HEADER_LEN + i * 2].toInt() and 0xFF
                val hi = buf[Proto.HEADER_LEN + i * 2 + 1].toInt()
                val s = ((hi shl 8) or lo).toShort()
                if (channels == 1) { samples[count++] = s; samples[count++] = s } else if (channels == 2) samples[count++] = s
            }
            jitter.push(h.seq, samples, count)
        }
    }

    private fun playLoop() {
        val t = track ?: return
        val block = ShortArray(Proto.FRAME_SAMPLES * Proto.CHANNELS)
        t.play()
        while (running) {
            jitter.pop(block)
            val v = volumePercent
            if (v != 100) for (i in block.indices) block[i] = (block[i] * v / 100).coerceIn(-32768, 32767).toShort()
            t.write(block, 0, block.size) // blocks at the device's pace
        }
        runCatching { t.stop() }
        t.release()
    }

    private fun controlLoop() {
        try {
            socket.soTimeout = 1000
            var lastSeen = Proto.nowMs()
            var lastPing = 0L
            while (running) {
                try {
                    val m = Proto.readMsg(input)
                    lastSeen = Proto.nowMs()
                    when (m.optString("type")) {
                        "ping" -> Proto.writeMsg(out, Proto.msg("pong", "t" to m.optLong("t")))
                        "pong" -> rttMs = (Proto.nowMs() - m.optLong("t")).toInt()
                        "format" -> jitter.reset()
                        "bye" -> break
                    }
                } catch (_: SocketTimeoutException) { }
                val now = Proto.nowMs()
                if (now - lastSeen > 5000) break
                if (now - lastPing >= 1000) { Proto.writeMsg(out, Proto.msg("ping", "t" to now)); lastPing = now }
            }
        } catch (e: Exception) {
            Log.i("EchoportPlayer", "disconnected: ${e.message}")
        }
        val wasRunning = running
        stop()
        if (wasRunning) onClosed()
    }
}
