package io.github.igrvlhlb.secrt.lan

import java.net.Inet4Address
import java.net.NetworkInterface

// Same as on the desktop JVM (both have java.net); there is no source set shared by just those two.
// Phones don't show pairing codes today, but this keeps the module usable on both sides.
actual fun localNetworkAddresses(): List<String> =
    runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        .filter { runCatching { it.isUp && !it.isLoopback && !it.isPointToPoint }.getOrDefault(false) }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }
        .distinct()
        .take(PairingInvite.MAX_ADDRESSES)
