package io.github.hkaaf.echoport.engine

import java.net.Inet4Address
import java.net.NetworkInterface

data class NetIface(val name: String, val ip: String, val kind: String)

/** Every IPv4 address this phone has (Wi-Fi, hotspot, USB tethering, ...). */
fun ipv4Interfaces(): List<NetIface> {
    val list = mutableListOf<NetIface>()
    val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return list
    for (ni in ifaces) {
        if (!ni.isUp || ni.isLoopback) continue
        for (addr in ni.inetAddresses) {
            if (addr is Inet4Address && !addr.isLinkLocalAddress) {
                list += NetIface(ni.name, addr.hostAddress ?: continue, classify(ni.name))
            }
        }
    }
    val order = listOf("wifi", "usb", "ethernet", "other", "mobile")
    return list.sortedBy { order.indexOf(it.kind) }
}

private fun classify(name: String): String {
    val n = name.lowercase()
    return when {
        n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") -> "wifi"
        n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> "usb"
        n.startsWith("eth") -> "ethernet"
        n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") -> "mobile"
        else -> "other"
    }
}
