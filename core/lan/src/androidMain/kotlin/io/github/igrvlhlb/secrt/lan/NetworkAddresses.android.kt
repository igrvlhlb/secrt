package io.github.igrvlhlb.secrt.lan

import java.net.Inet4Address
import java.net.NetworkInterface

// As on the desktop JVM (both have java.net; there is no source set shared by just those two), minus
// mobile data: carriers may hand out private addresses too, which no device on the Wi-Fi can reach,
// and each one in a pairing code costs the scanning side a connection timeout.
actual fun localNetworkAddresses(): List<String> =
    runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        .filter { runCatching { it.isUp && !it.isLoopback && !it.isPointToPoint }.getOrDefault(false) }
        .filterNot { network -> CELLULAR_INTERFACES.any { network.name.startsWith(it) } }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }
        .distinct()
        .take(PairingInvite.MAX_ADDRESSES)

/** Interface name prefixes of mobile data connections (Qualcomm, MediaTek, older/other modems). */
private val CELLULAR_INTERFACES = listOf("rmnet", "ccmni", "pdp", "v4-rmnet", "seth_lte")
