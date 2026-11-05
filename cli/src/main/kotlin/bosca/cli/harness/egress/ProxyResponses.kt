package bosca.cli.harness.egress

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully

private val REASONS = mapOf(
    200 to "Connection Established", 400 to "Bad Request", 403 to "Forbidden", 405 to "Method Not Allowed",
    407 to "Proxy Authentication Required", 408 to "Request Timeout", 431 to "Request Header Fields Too Large",
    502 to "Bad Gateway", 504 to "Gateway Timeout",
)

/** Writes a proxy status line, [headers] and, for non-200 statuses, an empty body that ends the connection. */
internal suspend fun ByteWriteChannel.writeProxyStatus(status: Int, headers: List<Pair<String, String>> = emptyList()) {
    val text = buildString {
        append("HTTP/1.1 ").append(status).append(' ').append(REASONS[status] ?: "Error").append("\r\n")
        headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
        if (status != 200) append("Content-Length: 0\r\nConnection: close\r\n")
        append("\r\n")
    }
    writeFully(text.toByteArray(Charsets.ISO_8859_1))
    flush()
}
