package bosca.cli.harness.egress

/**
 * One validated HTTP/1.x message head read from a proxy connection: its start line, header
 * fields in arrival order, and any bytes the peer sent after the head.
 */
internal class ProxyHead(val startLine: String, val headers: List<Pair<String, String>>, val remainder: ByteArray) {
    /** All values of the header [name], compared case-insensitively. */
    fun header(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
}
