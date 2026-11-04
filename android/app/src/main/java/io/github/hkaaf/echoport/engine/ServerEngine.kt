package io.github.hkaaf.echoport.engine

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

enum class Source(val wire: String) { MIC("mic"), SYSTEM("system") }

data class ServerConfig(
    val id: String,
    val name: String,
    val port: Int,
    val pin: String?,
    val source: Source,
    val gainPercent: Int,
    val eq: EqPreset,
    val noiseSuppression: Boolean,
)

data class PeerInfo(val id: String, val name: String, val platform: String, val addr: String, val rttMs: Int)

/**
 * Captures the mic or (Android 10+) other apps' audio and streams it to players.
 * Wire format: PROTOCOL.md.
 */
class ServerEngine(private val cfg: ServerConfig, private val projection: MediaProjection?) {
    private class Peer(val info: PeerInfo, val socket: Socket) {
        @Volatile var rttMs = 0
        @Volatile var udpTarget: SocketAddress? = null
        @Volatile var kicked = false
    }

    private val peers = ConcurrentHashMap<Int, Peer>()
    private lateinit var tcp: ServerSocket
    private lateinit var udp: DatagramSocket
    private var record: AudioRecord? = null
    private var noise: NoiseSuppressor? = null
    private val effects = EffectChain(cfg.gainPercent, cfg.eq)
    @Volatile private var running = false
    @Volatile var levelDb = -96f; private set

    val noiseSuppressionAvailable: Boolean get() = NoiseSuppressor.isAvailable()

    fun start() {
        tcp = ServerSocket(cfg.port)
        udp = DatagramSocket(cfg.port)
        record = buildRecorder()
        running = true
        thread("accept") { acceptLoop() }
        thread("udp-hi") { hiLoop() }
        thread("capture") { captureLoop() }
    }

    fun stop() {
        running = false
        for (p in peers.values) runCatching { p.socket.close() }
        runCatching { tcp.close() }
        runCatching { udp.close() }
        noise?.release()
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
    }

    fun peers(): List<PeerInfo> = peers.values.map { it.info.copy(rttMs = it.rttMs) }

    fun kick(peerId: String) {
        peers.values.filter { it.info.id == peerId }.forEach { it.kicked = true }
    }

    fun setEffects(gainPercent: Int, eq: EqPreset) {
        effects.gainPercent = gainPercent
        synchronized(effects) { effects.eq = eq }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before the server can start.
    private fun buildRecorder(): AudioRecord {
        val stereo = cfg.source == Source.SYSTEM
        val channelMask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.Builder()
            .setSampleRate(Proto.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(channelMask)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(Proto.SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(minBuf, 9600))
        if (stereo) {
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && projection != null) { "System audio needs Android 10" }
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            builder.setAudioPlaybackCaptureConfig(capture)
        } else {
            builder.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        val rec = builder.build()
        if (!stereo && cfg.noiseSuppression && NoiseSuppressor.isAvailable()) {
            noise = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
        }
        return rec
    }

    private fun captureLoop() {
        val rec = record ?: return
        val inCh = if (cfg.source == Source.SYSTEM) 2 else 1
        val input = ShortArray(Proto.FRAME_SAMPLES * inCh)
        val frame = FloatArray(Proto.FRAME_SAMPLES * Proto.CHANNELS)
        val packet = ByteArray(Proto.HEADER_LEN + frame.size * 2)
        val seqs = HashMap<Int, Int>()
        rec.startRecording()
        while (running) {
            var got = 0
            while (got < input.size && running) {
                val n = rec.read(input, got, input.size - got)
                if (n <= 0) break
                got += n
            }
            if (got < input.size) continue
            for (i in 0 until Proto.FRAME_SAMPLES) {
                val l = input[i * inCh] / 32768f
                val r = if (inCh == 2) input[i * 2 + 1] / 32768f else l
                frame[i * 2] = l; frame[i * 2 + 1] = r
            }
            synchronized(effects) { effects.process(frame) }
            levelDb = peakDb(frame)
            for (i in frame.indices) {
                val s = (frame[i].coerceIn(-1f, 1f) * 32767).toInt()
                packet[Proto.HEADER_LEN + i * 2] = (s and 0xFF).toByte() // little-endian PCM
                packet[Proto.HEADER_LEN + i * 2 + 1] = (s shr 8 and 0xFF).toByte()
            }
            for ((token, peer) in peers) {
                val target = peer.udpTarget ?: continue
                val seq = seqs[token] ?: 0
                Proto.writeHeader(packet, Proto.KIND_AUDIO, token, seq)
                runCatching { udp.send(DatagramPacket(packet, packet.size, target)) }
                seqs[token] = seq + 1
            }
        }
    }

    private fun hiLoop() {
        val buf = ByteArray(64)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            if (runCatching { udp.receive(p) }.isFailure) {
                if (!running) return
                continue
            }
            val h = Proto.parseHeader(buf, p.length) ?: continue
            if (h.kind == Proto.KIND_HI) peers[h.token]?.udpTarget = p.socketAddress
        }
    }

    private fun acceptLoop() {
        while (running) {
            val s = runCatching { tcp.accept() }.getOrNull() ?: continue
            thread("peer") {
                runCatching { servePeer(s) }.onFailure { Log.i(TAG, "player left: ${it.message}") }
                runCatching { s.close() }
            }
        }
    }

    private fun servePeer(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 5000
        val input = DataInputStream(socket.getInputStream().buffered())
        val out = DataOutputStream(socket.getOutputStream().buffered())
        val hello = Proto.readMsg(input)
        if (hello.optString("type") != "hello") return
        fun refuse(code: String, text: String) = Proto.writeMsg(out, Proto.msg("error", "code" to code, "message" to text))
        if (hello.optInt("proto") != Proto.VERSION) return refuse("version", "Unsupported protocol version")
        if (cfg.pin != null) {
            val pin = hello.optString("pin", "")
            if (pin.isEmpty()) return refuse("pin_required", "This server needs a PIN")
            if (pin != cfg.pin) return refuse("bad_pin", "Wrong PIN")
        }
        val token = Random.nextInt()
        Proto.writeMsg(out, Proto.msg(
            "welcome", "proto" to Proto.VERSION, "id" to cfg.id, "name" to cfg.name, "source" to cfg.source.wire,
            "token" to (token.toLong() and 0xFFFFFFFFL), "udpPort" to cfg.port, "sampleRate" to Proto.SAMPLE_RATE,
            "channels" to Proto.CHANNELS, "frameMs" to Proto.FRAME_MS, "codec" to "pcm_s16le",
        ))
        val info = PeerInfo(hello.optString("id"), hello.optString("name"), hello.optString("platform"),
            socket.inetAddress.hostAddress ?: "", 0)
        val peer = Peer(info, socket)
        peers[token] = peer
        try {
            socket.soTimeout = 1000
            var lastSeen = Proto.nowMs()
            var lastPing = 0L
            while (running && !peer.kicked) {
                try {
                    val m = Proto.readMsg(input)
                    lastSeen = Proto.nowMs()
                    when (m.optString("type")) {
                        "ping" -> Proto.writeMsg(out, Proto.msg("pong", "t" to m.optLong("t")))
                        "pong" -> peer.rttMs = (Proto.nowMs() - m.optLong("t")).toInt()
                        "bye" -> return
                    }
                } catch (_: SocketTimeoutException) { }
                val now = Proto.nowMs()
                if (now - lastSeen > 5000) return
                if (now - lastPing >= 1000) { Proto.writeMsg(out, Proto.msg("ping", "t" to now)); lastPing = now }
            }
            runCatching { Proto.writeMsg(out, Proto.msg("bye")) }
        } finally {
            peers.remove(token)
        }
    }

    companion object {
        private const val TAG = "EchoportServer"
        fun thread(name: String, body: () -> Unit) = Thread(body, "echoport-$name").apply { isDaemon = true; start() }
    }
}
