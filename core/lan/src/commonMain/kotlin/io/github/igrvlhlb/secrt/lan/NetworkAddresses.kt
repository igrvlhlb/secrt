package io.github.igrvlhlb.secrt.lan

/**
 * This device's IPv4 addresses on local networks (private ranges, interfaces that are up), best
 * candidates first, for the pairing QR code.
 */
expect fun localNetworkAddresses(): List<String>
