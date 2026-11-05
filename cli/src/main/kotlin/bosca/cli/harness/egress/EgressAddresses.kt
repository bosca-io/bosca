package bosca.cli.harness.egress

import java.net.InetAddress

/**
 * Classifies resolved addresses for administrator-approved egress destinations. Unspecified,
 * loopback, private, CGNAT, link-local (including cloud metadata), IETF protocol assignment,
 * benchmarking, multicast, reserved and broadcast IPv4 ranges are not public, nor are the
 * matching IPv6 ranges. An IPv6 address embedding an IPv4 address (IPv4-compatible, IPv4-mapped,
 * IPv4-translated, NAT64 or 6to4) is classified by the address it reaches.
 */
internal object EgressAddresses {
    private val IPV4_BLOCKED = listOf(
        "0.0.0.0" to 8, "10.0.0.0" to 8, "100.64.0.0" to 10, "127.0.0.0" to 8, "169.254.0.0" to 16,
        "172.16.0.0" to 12, "192.0.0.0" to 24, "192.168.0.0" to 16, "198.18.0.0" to 15, "224.0.0.0" to 4,
        "240.0.0.0" to 4, "255.255.255.255" to 32,
    ).map { (network, prefix) -> ipv4(network) to prefix }
    private val NAT64 = InetAddress.getByName("64:ff9b::").address
    private val IPV6_BLOCKED = listOf(
        "64:ff9b:1::" to 48, // local-use NAT64
        "100::" to 64, // discard-only
        "2001::" to 23, // IETF protocol assignments, including Teredo and benchmarking
        "2001:db8::" to 32, // documentation
        "fc00::" to 7, // unique local
        "fe80::" to 10, // link-local
        "fec0::" to 10, // deprecated site-local
        "ff00::" to 8, // multicast
    ).map { (network, prefix) -> InetAddress.getByName(network).address to prefix }

    /** Returns whether [address] may be reached through the egress proxy for a looked-up destination. */
    fun isPublic(address: InetAddress): Boolean = isPublic(address.address)

    /** Classifies a raw 4-byte IPv4 or 16-byte IPv6 address. */
    fun isPublic(bytes: ByteArray): Boolean = when (bytes.size) {
        4 -> isPublicIpv4(bytes)
        16 -> isPublicIpv6(bytes)
        else -> false
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val value = bytes.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
        return IPV4_BLOCKED.none { (network, prefix) ->
            val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
            (value and mask) == (network and mask)
        }
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        embeddedIpv4(bytes)?.let { return isPublicIpv4(it) }
        return IPV6_BLOCKED.none { (network, prefix) -> matches(bytes, network, prefix) }
    }

    /** The IPv4 address [bytes] routes to, including :: and ::1, whose embedded forms fall in 0.0.0.0/8. */
    private fun embeddedIpv4(bytes: ByteArray): ByteArray? {
        fun zero(range: IntRange) = range.all { bytes[it].toInt() == 0 }
        fun ones(index: Int) = (bytes[index].toInt() and 0xff) == 0xff && (bytes[index + 1].toInt() and 0xff) == 0xff
        return when {
            zero(0..11) -> bytes.copyOfRange(12, 16) // ::a.b.c.d
            zero(0..9) && ones(10) -> bytes.copyOfRange(12, 16) // ::ffff:a.b.c.d
            zero(0..7) && ones(8) && zero(10..11) -> bytes.copyOfRange(12, 16) // ::ffff:0:a.b.c.d
            matches(bytes, NAT64, 96) -> bytes.copyOfRange(12, 16) // 64:ff9b::a.b.c.d
            (bytes[0].toInt() and 0xff) == 0x20 && bytes[1].toInt() == 0x02 -> bytes.copyOfRange(2, 6) // 2002:a.b.c.d::
            else -> null
        }
    }

    private fun matches(bytes: ByteArray, network: ByteArray, prefix: Int): Boolean = (0 until prefix).all { bit ->
        val mask = 0x80 shr (bit % 8)
        (bytes[bit / 8].toInt() and mask) == (network[bit / 8].toInt() and mask)
    }

    private fun ipv4(text: String): Long = text.split('.').fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
}
