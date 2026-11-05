package bosca.cli.harness.egress

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

/** The largest accepted message head, including its start line and terminating blank line. */
internal const val MAX_PROXY_HEAD_BYTES = 16 * 1024

private val TERMINATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

/**
 * Reads one CRLF-delimited HTTP/1.x message head of at most [limit] bytes. Bytes received after
 * the head are retained in [ProxyHead.remainder] so they can be relayed.
 *
 * @throws ProxyHeadException with status 431 when the head exceeds [limit], or 400 when the peer
 * closes early or sends a malformed head.
 */
internal suspend fun readProxyHead(input: ByteReadChannel, limit: Int = MAX_PROXY_HEAD_BYTES): ProxyHead {
    val buffer = ByteArray(limit)
    var size = 0
    var scanned = 0
    while (true) {
        val end = indexOfTerminator(buffer, scanned, size)
        if (end >= 0) return parseProxyHead(String(buffer, 0, end, Charsets.ISO_8859_1), buffer.copyOfRange(end + TERMINATOR.size, size))
        scanned = maxOf(0, size - TERMINATOR.size + 1)
        if (size == limit) throw ProxyHeadException(431, "Message head too large")
        val read = input.readAvailable(buffer, size, limit - size)
        if (read < 0) throw ProxyHeadException(400, "Connection closed before the message head ended")
        size += read
    }
}

/** Validates a head's text (without its terminating blank line) and splits it into fields. */
internal fun parseProxyHead(text: String, remainder: ByteArray = ByteArray(0)): ProxyHead {
    val lines = text.split("\r\n")
    val startLine = lines.first()
    if (startLine.isEmpty() || startLine.any { it.code < 0x20 || it.code == 0x7f }) throw ProxyHeadException(400, "Malformed start line")
    val headers = lines.drop(1).map { line ->
        val colon = line.indexOf(':')
        if (colon <= 0) throw ProxyHeadException(400, "Malformed header field")
        val name = line.substring(0, colon)
        val value = line.substring(colon + 1).trim(' ', '\t')
        if (!TOKEN.matches(name) || value.any { (it.code < 0x20 && it != '\t') || it.code == 0x7f }) throw ProxyHeadException(400, "Malformed header field")
        name to value
    }
    return ProxyHead(startLine, headers, remainder)
}

private fun indexOfTerminator(buffer: ByteArray, from: Int, size: Int): Int {
    var index = from
    while (index + TERMINATOR.size <= size) {
        if (TERMINATOR.indices.all { buffer[index + it] == TERMINATOR[it] }) return index
        index++
    }
    return -1
}
