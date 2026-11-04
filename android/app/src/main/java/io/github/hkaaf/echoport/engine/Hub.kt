package io.github.hkaaf.echoport.engine

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.hkaaf.echoport.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class HubState(
    val serverRunning: Boolean = false,
    val levelDb: Float = -96f,
    val peers: List<PeerInfo> = emptyList(),
    val player: PlayerStatus? = null,
    val connecting: Boolean = false,
    val serverError: String = "",
    /** A protocol error code (pin_required, bad_pin, ...), "lost", or a message. */
    val playerError: String = "",
)

/**
 * Process-wide owner of the server and player. The UI calls into it; the foreground
 * [EchoService] keeps the process alive and hands over things only a service can get
 * (the media projection for system-audio capture).
 */
object Hub {
    lateinit var prefs: Prefs; private set
    lateinit var discovery: Discovery; private set
    private lateinit var app: Context

    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = _state

    @Volatile private var server: ServerEngine? = null
    @Volatile private var player: PlayerEngine? = null
    private var pendingPlayer: Triple<List<String>, Int, String?>? = null
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = Prefs(app)
        discovery = Discovery(app) { prefs.id }
    }

    val isActive get() = server != null || player != null

    // ---- server ----

    /** For system audio, pass the screen-capture consent result from the activity. */
    fun startServer(context: Context, projectionCode: Int = 0, projectionData: Intent? = null) {
        val intent = Intent(context, EchoService::class.java).setAction(EchoService.ACTION_SERVER)
            .putExtra(EchoService.EXTRA_CODE, projectionCode)
            .putExtra(EchoService.EXTRA_DATA, projectionData)
        ContextCompat.startForegroundService(context, intent)
    }

    internal fun launchServer(projection: MediaProjection?) {
        stopServerInternal()
        val s = prefs.state.value
        val engine = ServerEngine(ServerConfig(prefs.id, prefs.name, s.port, if (s.requirePin) s.pin else null,
            s.source, s.gainPercent, s.eq, s.noiseSuppression), projection)
        Thread {
            try {
                engine.start()
                server = engine
                discovery.advertise(prefs.id, prefs.name, s.port, s.source, s.requirePin)
                _state.value = _state.value.copy(serverRunning = true, serverError = "")
                tick()
            } catch (e: Exception) {
                engine.stop()
                _state.value = _state.value.copy(serverRunning = false, serverError = e.message ?: e.toString())
                releaseServiceIfIdle()
            }
        }.start()
    }

    fun stopServer() {
        stopServerInternal()
        releaseServiceIfIdle()
    }

    private fun stopServerInternal() {
        server?.let { it.stop(); discovery.stopAdvertising() }
        server = null
        _state.value = _state.value.copy(serverRunning = false, peers = emptyList(), levelDb = -96f)
    }

    /** Restart so a changed source, PIN or port applies. System audio needs fresh consent, so it's left to the UI. */
    fun restartServerIfRunning(context: Context) {
        if (server != null && prefs.state.value.source == Source.MIC) startServer(context)
    }

    fun setEffects() {
        val s = prefs.state.value
        server?.setEffects(s.gainPercent, s.eq)
    }

    fun kick(peerId: String) = server?.kick(peerId)

    // ---- player ----

    fun connect(context: Context, addrs: List<String>, port: Int, pin: String?) {
        pendingPlayer = Triple(addrs, port, pin)
        _state.value = _state.value.copy(connecting = true, playerError = "")
        ContextCompat.startForegroundService(context, Intent(context, EchoService::class.java).setAction(EchoService.ACTION_PLAYER))
    }

    internal fun launchPlayer() {
        val (addrs, port, pin) = pendingPlayer ?: return
        pendingPlayer = null
        player?.stop()
        val s = prefs.state.value
        var engine: PlayerEngine? = null
        engine = PlayerEngine(PlayerConfig(prefs.id, prefs.name, pin, s.bufferMs, s.volume)) {
            if (player === engine) {
                player = null
                _state.value = _state.value.copy(player = null, playerError = "lost")
                releaseServiceIfIdle()
            }
        }
        Thread {
            try {
                engine.connect(addrs, port)
                player = engine
                prefs.update { it.copy(lastServerAddrs = addrs.joinToString(","), lastServerPort = port) }
                _state.value = _state.value.copy(connecting = false, playerError = "")
                tick()
            } catch (e: RefusedException) {
                _state.value = _state.value.copy(connecting = false, playerError = e.code)
                releaseServiceIfIdle()
            } catch (e: Exception) {
                _state.value = _state.value.copy(connecting = false, playerError = e.message ?: e.toString())
                releaseServiceIfIdle()
            }
        }.start()
    }

    fun disconnect() {
        val p = player
        player = null
        p?.stop()
        prefs.update { it.copy(lastServerAddrs = "") }
        _state.value = _state.value.copy(player = null)
        releaseServiceIfIdle()
    }

    fun setVolume(percent: Int) { player?.volumePercent = percent }
    fun setBuffer(ms: Int) { player?.setBuffer(ms) }

    // ---- status ----

    private var ticking = false

    private fun tick() = main.post {
        if (ticking) return@post
        ticking = true
        val loop = object : Runnable {
            override fun run() {
                val srv = server
                val ply = player
                _state.value = _state.value.copy(
                    serverRunning = srv != null,
                    levelDb = srv?.levelDb ?: -96f,
                    peers = srv?.peers() ?: emptyList(),
                    player = ply?.status(),
                )
                if (srv != null || ply != null) main.postDelayed(this, 500) else ticking = false
            }
        }
        loop.run()
    }

    private fun releaseServiceIfIdle() {
        if (!isActive) app.startService(Intent(app, EchoService::class.java).setAction(EchoService.ACTION_IDLE))
    }
}
