package bosca.cli.harness.egress

/** A parsed HTTP/1.x request line. */
internal data class ProxyRequestLine(val method: String, val target: String, val version: String) {
    companion object {
        private val METHOD = Regex("[A-Z]{1,16}")
        private val VERSION = Regex("HTTP/1\\.[01]")

        /** @throws ProxyHeadException with status 400 when [line] is not `METHOD SP target SP HTTP/1.x`. */
        fun parse(line: String): ProxyRequestLine {
            val parts = line.split(' ')
            if (parts.size != 3 || !METHOD.matches(parts[0]) || parts[1].isEmpty() || parts[1].length > 1024 || !VERSION.matches(parts[2]) ||
                parts[1].any { it.code !in 0x21..0x7e }) throw ProxyHeadException(400, "Malformed request line")
            return ProxyRequestLine(parts[0], parts[1], parts[2])
        }
    }
}
