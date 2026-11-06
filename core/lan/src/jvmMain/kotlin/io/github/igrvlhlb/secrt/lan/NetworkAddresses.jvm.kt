package io.github.igrvlhlb.secrt.lan

import java.net.Inet4Address
import java.net.NetworkInterface

// Same as on Android (both have java.net); there is no source set shared by just those two.
actual fun localNetworkAddresses(): List<String> =
    runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        .filter { runCatching { it.isUp && !it.isLoopback && !it.isPointToPoint }.getOrDefault(false) }
        // Wi-Fi/Ethernet before VPN tunnels, container bridges and VMs.
        .sortedBy { if (it.isVirtual || VIRTUAL_PREFIXES.any(it.name::startsWith)) 1 else 0 }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }
        .distinct()
        .take(PairingInvite.MAX_ADDRESSES)

private val VIRTUAL_PREFIXES = listOf("utun", "docker", "vbox", "vmnet", "bridge", "veth")
