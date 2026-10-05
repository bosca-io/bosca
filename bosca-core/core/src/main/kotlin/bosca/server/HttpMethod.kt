package bosca.server

/**
 * Represents an HTTP request method.
 *
 * Wraps the standard HTTP methods for type-safe routing and request handling.
 */
data class HttpMethod(val value: String) {
    companion object {
        val Get = HttpMethod("GET")
        val Post = HttpMethod("POST")
        val Put = HttpMethod("PUT")
        val Delete = HttpMethod("DELETE")
        val Patch = HttpMethod("PATCH")
        val Head = HttpMethod("HEAD")
        val Options = HttpMethod("OPTIONS")

        /** Parses a raw method string into an [HttpMethod], performing a case-insensitive match. */
        fun parse(method: String): HttpMethod = when (method.uppercase()) {
            "GET" -> Get; "POST" -> Post; "PUT" -> Put; "DELETE" -> Delete
            "PATCH" -> Patch; "HEAD" -> Head; "OPTIONS" -> Options
            else -> HttpMethod(method.uppercase())
        }
    }
}
