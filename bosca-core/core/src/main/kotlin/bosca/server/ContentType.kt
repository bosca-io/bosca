package bosca.server

/**
 * Represents an HTTP content type (MIME type) with optional parameters.
 *
 * Provides structured access to the content type, subtype, and parameters such as charset,
 * used throughout the server framework for content negotiation and response formatting.
 */
data class ContentType(
    val contentType: String,
    val contentSubtype: String,
    val parameters: Map<String, String> = emptyMap()
) {
    /** Returns the full MIME type string without parameters (e.g., "application/json"). */
    fun withoutParameters(): ContentType = copy(parameters = emptyMap())

    /** Checks whether this content type matches the given pattern, supporting wildcard subtypes. */
    fun match(pattern: String): Boolean {
        val parsed = parse(pattern)
        return (parsed.contentType == "*" || parsed.contentType.equals(contentType, ignoreCase = true)) &&
                (parsed.contentSubtype == "*" || parsed.contentSubtype.equals(contentSubtype, ignoreCase = true))
    }

    override fun toString(): String {
        val base = "$contentType/$contentSubtype"
        return if (parameters.isEmpty()) base
        else parameters.entries.joinToString("; ", prefix = "$base; ") { "${it.key}=${quoteIfNeeded(it.value)}" }
    }

    companion object {
        /** Characters that require quoting per RFC 7231 token rules. */
        private val NEEDS_QUOTING = setOf(' ', '\t', ';', '"', '\\', ',', '/')

        /** Wraps the value in quotes if it contains characters outside the token character set. */
        private fun quoteIfNeeded(value: String): String {
            if (value.isEmpty() || value.any { it in NEEDS_QUOTING }) {
                return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }
            return value
        }

        /**
         * Parses a raw content type string like "application/json; charset=utf-8" into a [ContentType].
         *
         * @throws IllegalArgumentException if the value is blank or does not contain a type/subtype separator
         */
        fun parse(value: String): ContentType {
            require(value.isNotBlank()) { "Content type string must not be blank" }
            val parts = value.split(";").map { it.trim() }
            val mediaType = parts[0]
            require(mediaType.contains('/')) { "Invalid content type format, expected type/subtype: '$value'" }
            val (type, subtype) = mediaType.split("/", limit = 2).let {
                it[0].lowercase() to it[1].lowercase()
            }
            val params = parts.drop(1).filter { it.isNotBlank() }.associate {
                val (k, v) = it.split("=", limit = 2).let { p ->
                    if (p.size == 2) p[0].trim().lowercase() to p[1].trim().removeSurrounding("\"") else p[0].trim().lowercase() to ""
                }
                k to v
            }
            return ContentType(type, subtype, params)
        }
    }

    /** Common content type constants for application-level MIME types. */
    object Application {
        val Json = ContentType("application", "json")
        val OctetStream = ContentType("application", "octet-stream")
        val FormUrlEncoded = ContentType("application", "x-www-form-urlencoded")
        val Xml = ContentType("application", "xml")
        val ProtoBuf = ContentType("application", "protobuf")
        val Any = ContentType("application", "*")
    }

    /** Common content type constants for text-level MIME types. */
    object Text {
        val Plain = ContentType("text", "plain")
        val Html = ContentType("text", "html")
        val Css = ContentType("text", "css")
        val JavaScript = ContentType("text", "javascript")
        val EventStream = ContentType("text", "event-stream")
        val Xml = ContentType("text", "xml")
        val Any = ContentType("text", "*")
    }

    /** Common content type constants for multipart MIME types. */
    object MultiPart {
        val FormData = ContentType("multipart", "form-data")
        val Any = ContentType("multipart", "*")
    }

    /** Common content type constants for image MIME types. */
    object Image {
        val Png = ContentType("image", "png")
        val Jpeg = ContentType("image", "jpeg")
        val Gif = ContentType("image", "gif")
        val Any = ContentType("image", "*")
    }
}
