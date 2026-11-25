package bosca.cli.harness.egress

/**
 * A normalized egress destination: a lowercase host without a trailing dot and a TCP port.
 * Wildcards, IPv6 literals, userinfo, paths and queries are never accepted.
 */
internal data class EgressDestination(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"

    companion object {
        const val DEFAULT_PORT = 443
        private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

        /**
         * Parses `host[:port]`. When [requirePort] is false a missing port defaults to [DEFAULT_PORT].
         *
         * @throws IllegalArgumentException when the value is not a valid destination.
         */
        fun parse(value: String, requirePort: Boolean = false): EgressDestination {
            require(value.isNotEmpty() && value.length <= 260) { "Invalid egress destination" }
            require(value.count { it == ':' } <= 1 && '[' !in value && ']' !in value) { "IPv6 literal destinations are not supported" }
            val separator = value.lastIndexOf(':')
            val rawHost = if (separator >= 0) value.substring(0, separator) else value
            require(!requirePort || separator >= 0) { "Egress destination requires a port" }
            val port = if (separator >= 0) {
                val text = value.substring(separator + 1)
                require(text.isNotEmpty() && text.length <= 5 && text.all { it in '0'..'9' }) { "Invalid egress destination port" }
                text.toInt().also { require(it in 1..65535) { "Invalid egress destination port" } }
            } else DEFAULT_PORT
            val host = rawHost.lowercase().removeSuffix(".")
            require(host.isNotEmpty() && host.length <= 253) { "Invalid egress destination host" }
            require(host.split('.').all(LABEL::matches)) { "Invalid egress destination host" }
            return EgressDestination(host, port)
        }

        /** Parses [value] like [parse], returning null instead of throwing for an invalid destination. */
        fun parseOrNull(value: String, requirePort: Boolean = false): EgressDestination? =
            try { parse(value, requirePort) } catch (_: IllegalArgumentException) { null }
    }
}
