package bosca.cli.harness.egress

import java.net.InetAddress
import kotlin.test.*

class EgressAddressesTest {
    private fun address(text: String) = InetAddress.getByName(text)

    @Test fun `non-public IPv4 ranges are rejected at their boundaries`() {
        for (text in listOf("0.0.0.0", "0.255.255.255", "10.0.0.0", "10.255.255.255", "100.64.0.0", "100.127.255.255", "127.0.0.1",
            "169.254.169.254", "172.16.0.0", "172.31.255.255", "192.0.0.1", "192.168.1.1", "198.18.0.0", "198.19.255.255",
            "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255")) {
            assertFalse(EgressAddresses.isPublic(address(text)), text)
        }
        for (text in listOf("1.1.1.1", "8.8.8.8", "9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0", "126.255.255.255", "128.0.0.0",
            "169.253.255.255", "169.255.0.0", "172.15.255.255", "172.32.0.0", "192.0.1.0", "192.167.255.255", "192.169.0.0",
            "198.17.255.255", "198.20.0.0", "223.255.255.255")) {
            assertTrue(EgressAddresses.isPublic(address(text)), text)
        }
    }

    @Test fun `non-public IPv6 ranges and IPv4-mapped private addresses are rejected`() {
        for (text in listOf("::", "::1", "fc00::1", "fdff:ffff::1", "fe80::1", "febf::1", "fec0::1", "feff::1", "ff02::1", "ff00::",
            "100::1", "2001::1", "2001:2::1", "2001:1ff::1", "2001:db8::1", "64:ff9b:1::1")) {
            assertFalse(EgressAddresses.isPublic(address(text)), text)
        }
        for (text in listOf("2606:4700:4700::1111", "2001:4860:4860::8888", "2001:200::1", "2001:db9::1", "101::1", "fb00::1")) {
            assertTrue(EgressAddresses.isPublic(address(text)), text)
        }
        fun mapped(a: Int, b: Int, c: Int, d: Int) = ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = a.toByte(); it[13] = b.toByte(); it[14] = c.toByte(); it[15] = d.toByte() }
        assertFalse(EgressAddresses.isPublic(mapped(127, 0, 0, 1)))
        assertFalse(EgressAddresses.isPublic(mapped(169, 254, 169, 254)))
        assertFalse(EgressAddresses.isPublic(mapped(10, 1, 2, 3)))
        assertTrue(EgressAddresses.isPublic(mapped(1, 1, 1, 1)))
        assertFalse(EgressAddresses.isPublic(ByteArray(5)))
    }

    @Test fun `IPv6 addresses embedding IPv4 are classified by the address they reach`() {
        for (text in listOf("::a9fe:a9fe", "::7f00:1", "::ffff:0:a9fe:a9fe", "64:ff9b::a9fe:a9fe", "64:ff9b::a00:1", "2002:a9fe:a9fe::", "2002:a00:1::1")) {
            assertFalse(EgressAddresses.isPublic(address(text)), text)
        }
        for (text in listOf("::101:101", "::ffff:0:101:101", "64:ff9b::101:101", "2002:101:101::1")) {
            assertTrue(EgressAddresses.isPublic(address(text)), text)
        }
    }
}
