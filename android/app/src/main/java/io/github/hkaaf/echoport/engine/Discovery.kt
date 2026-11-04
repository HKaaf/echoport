package io.github.hkaaf.echoport.engine

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredServer(
    val id: String,
    val name: String,
    val addrs: List<String>,
    val port: Int,
    val source: String,
    val pinRequired: Boolean,
)

/** Finds Echoport servers via DNS-SD and announces our own server. */
class Discovery(context: Context, private val ownId: () -> String) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers
    private val found = LinkedHashMap<String, DiscoveredServer>()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)
    private var browsing = false
    private var registration: NsdManager.RegistrationListener? = null

    private val browseListener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) { resolveQueue.add(info); resolveNext() }
        override fun onServiceLost(info: NsdServiceInfo) = synchronized(found) {
            found.remove(info.serviceName); publish()
        }
        override fun onDiscoveryStarted(type: String) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, code: Int) { browsing = false }
        override fun onStopDiscoveryFailed(type: String, code: Int) {}
    }

    // NsdManager can only resolve one service at a time on older Android versions.
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (!resolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll() ?: run { resolving.set(false); return }
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val attr = { k: String -> info.attributes[k]?.toString(Charsets.UTF_8) ?: "" }
                val id = attr("id")
                val host = info.host?.hostAddress
                if (id.isNotEmpty() && id != ownId() && host != null && !host.contains(':')) {
                    synchronized(found) {
                        found[info.serviceName] = DiscoveredServer(id, attr("name").ifEmpty { info.serviceName },
                            listOf(host), info.port, attr("src"), attr("pin") == "1")
                        publish()
                    }
                }
                resolving.set(false); resolveNext()
            }
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { resolving.set(false); resolveNext() }
        })
    }

    private fun publish() {
        _servers.value = found.values.sortedBy { it.name.lowercase() }
    }

    fun startBrowsing() {
        if (browsing) return
        browsing = true
        runCatching { nsd.discoverServices(Proto.NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, browseListener) }
            .onFailure { browsing = false }
    }

    fun stopBrowsing() {
        if (!browsing) return
        browsing = false
        runCatching { nsd.stopServiceDiscovery(browseListener) }
        synchronized(found) { found.clear(); publish() }
    }

    fun advertise(id: String, name: String, port: Int, source: Source, pinRequired: Boolean) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = id
            serviceType = Proto.NSD_TYPE
            this.port = port
            setAttribute("id", id)
            setAttribute("name", name)
            setAttribute("ver", Proto.VERSION.toString())
            setAttribute("src", source.wire)
            setAttribute("pin", if (pinRequired) "1" else "0")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stopAdvertising() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }
}
