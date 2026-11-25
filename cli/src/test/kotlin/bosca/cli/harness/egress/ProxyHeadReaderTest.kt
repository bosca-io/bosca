package bosca.cli.harness.egress

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ProxyHeadReaderTest {
    private fun read(text: String, limit: Int = MAX_PROXY_HEAD_BYTES) = runBlocking { readProxyHead(ByteReadChannel(text.toByteArray(Charsets.ISO_8859_1)), limit) }

    @Test fun `heads keep header order and the bytes that follow`() {
        val head = read("CONNECT api.example.com:443 HTTP/1.1\r\nHost: api.example.com:443\r\nproxy-authorization:  Basic abc \r\n\r\nearly")
        assertEquals("CONNECT api.example.com:443 HTTP/1.1", head.startLine)
        assertEquals(listOf("Basic abc"), head.header("Proxy-Authorization"))
        assertEquals("early", head.remainder.toString(Charsets.ISO_8859_1))
        assertEquals(ProxyRequestLine("CONNECT", "api.example.com:443", "HTTP/1.1"), ProxyRequestLine.parse(head.startLine))
    }

    @Test fun `oversized truncated and malformed heads are rejected`() {
        assertEquals(431, assertFailsWith<ProxyHeadException> { read("CONNECT a:443 HTTP/1.1\r\nX: " + "a".repeat(MAX_PROXY_HEAD_BYTES) + "\r\n\r\n") }.status)
        assertEquals(431, assertFailsWith<ProxyHeadException> { read("CONNECT a:443 HTTP/1.1\r\n\r\n", limit = 10) }.status)
        for (text in listOf("CONNECT a:443 HTTP/1.1\r\n", "", "CONNECT a:443 HTTP/1.1\r\nBad Header: x\r\n\r\n", "CONNECT a:443 HTTP/1.1\r\n folded\r\n\r\n",
            "CONNECT a:443 HTTP/1.1\r\nX: a\nb\r\n\r\n", "CONNECT a:443 HTTP/1.1\r\n: x\r\n\r\n", "\r\n\r\n")) {
            assertEquals(400, assertFailsWith<ProxyHeadException>(text) { read(text) }.status, text)
        }
        for (line in listOf("CONNECT", "CONNECT a:443", "CONNECT  a:443 HTTP/1.1", "CONNECT a:443 HTTP/2", "connect a:443 HTTP/1.1", "CONNECT a:443 HTTP/1.1 extra")) {
            assertEquals(400, assertFailsWith<ProxyHeadException>(line) { ProxyRequestLine.parse(line) }.status)
        }
    }
}
