package dev.eno2.openrgbremote.sdk.discovery

import java.net.Inet4Address
import java.net.InetAddress

/**
 * IPv4 subnet arithmetic for the network scan.
 *
 * Large networks are capped: scanning a /16 (65k hosts) from a phone would take minutes and
 * look like an attack to IDS systems. For prefixes shorter than [MIN_SCAN_PREFIX] only the
 * /24 around the phone's own address is scanned.
 */
public data class Ipv4Subnet(val network: Int, val prefixLength: Int) {
    init {
        require(prefixLength in 0..32) { "Invalid prefix length $prefixLength" }
    }

    private val mask: Int get() = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))

    public val size: Long get() = 1L shl (32 - prefixLength)

    /** Usable host addresses (without network and broadcast address for prefixes <= 30). */
    public fun hosts(): List<Inet4Address> {
        val first: Long
        val last: Long
        val base = network.toLong() and 0xFFFF_FFFFL
        if (prefixLength >= 31) {
            first = base
            last = base + size - 1
        } else {
            first = base + 1
            last = base + size - 2
        }
        return (first..last).map { toInet(it.toInt()) }
    }

    public operator fun contains(address: Inet4Address): Boolean = (toInt(address) and mask) == network

    override fun toString(): String = "${toInet(network).hostAddress}/$prefixLength"

    public companion object {
        /** Smallest prefix that is scanned completely (/22 = 1022 hosts). */
        public const val MIN_SCAN_PREFIX: Int = 22

        public fun of(address: Inet4Address, prefixLength: Int): Ipv4Subnet {
            val mask = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
            return Ipv4Subnet(toInt(address) and mask, prefixLength)
        }

        /** Subnet to scan for an interface address: the real subnet, or its /24 if it is too large. */
        public fun scanRange(address: Inet4Address, prefixLength: Int): Ipv4Subnet =
            of(address, if (prefixLength < MIN_SCAN_PREFIX) 24 else prefixLength)

        /** Parses "a.b.c.d/n". */
        public fun parse(cidr: String): Ipv4Subnet? {
            val parts = cidr.trim().split('/')
            if (parts.size != 2) return null
            val prefix = parts[1].toIntOrNull()?.takeIf { it in 0..32 } ?: return null
            val address = parseIpv4(parts[0]) ?: return null
            return of(address, prefix)
        }

        /** Parses a dotted IPv4 literal without DNS lookups; null if [text] is not one. */
        public fun parseIpv4(text: String): Inet4Address? {
            val octets = text.trim().split('.')
            if (octets.size != 4) return null
            val bytes = ByteArray(4)
            for (i in 0 until 4) {
                val v = octets[i].toIntOrNull() ?: return null
                if (v !in 0..255 || octets[i].isEmpty() || (octets[i].length > 1 && octets[i][0] == '0')) return null
                bytes[i] = v.toByte()
            }
            return InetAddress.getByAddress(bytes) as Inet4Address
        }

        internal fun toInt(address: Inet4Address): Int {
            val b = address.address
            return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        }

        internal fun toInet(value: Int): Inet4Address = InetAddress.getByAddress(
            byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()),
        ) as Inet4Address
    }
}
