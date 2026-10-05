package bosca.server

import io.netty.handler.codec.http.HttpResponseStatus

/**
 * Represents an HTTP status code with its numeric value and description.
 *
 * Provides a type-safe wrapper around HTTP status codes for use throughout the server framework,
 * mapping directly to Netty's [HttpResponseStatus] for efficient response generation.
 */
data class HttpStatusCode(val value: Int, val description: String) {

    /** Converts this status code to Netty's [HttpResponseStatus] for use in HTTP responses. */
    internal fun toNetty(): HttpResponseStatus = HttpResponseStatus.valueOf(value)

    override fun toString(): String = "$value $description"

    companion object {
        val Continue = HttpStatusCode(100, "Continue")
        val SwitchingProtocols = HttpStatusCode(101, "Switching Protocols")
        val Processing = HttpStatusCode(102, "Processing")

        val OK = HttpStatusCode(200, "OK")
        val Created = HttpStatusCode(201, "Created")
        val Accepted = HttpStatusCode(202, "Accepted")
        val NonAuthoritativeInformation = HttpStatusCode(203, "Non-Authoritative Information")
        val NoContent = HttpStatusCode(204, "No Content")
        val ResetContent = HttpStatusCode(205, "Reset Content")
        val PartialContent = HttpStatusCode(206, "Partial Content")
        val MultiStatus = HttpStatusCode(207, "Multi-Status")

        val MultipleChoices = HttpStatusCode(300, "Multiple Choices")
        val MovedPermanently = HttpStatusCode(301, "Moved Permanently")
        val Found = HttpStatusCode(302, "Found")
        val SeeOther = HttpStatusCode(303, "See Other")
        val NotModified = HttpStatusCode(304, "Not Modified")
        val TemporaryRedirect = HttpStatusCode(307, "Temporary Redirect")
        val PermanentRedirect = HttpStatusCode(308, "Permanent Redirect")

        val BadRequest = HttpStatusCode(400, "Bad Request")
        val Unauthorized = HttpStatusCode(401, "Unauthorized")
        val PaymentRequired = HttpStatusCode(402, "Payment Required")
        val Forbidden = HttpStatusCode(403, "Forbidden")
        val NotFound = HttpStatusCode(404, "Not Found")
        val MethodNotAllowed = HttpStatusCode(405, "Method Not Allowed")
        val NotAcceptable = HttpStatusCode(406, "Not Acceptable")
        val RequestTimeout = HttpStatusCode(408, "Request Timeout")
        val Conflict = HttpStatusCode(409, "Conflict")
        val Gone = HttpStatusCode(410, "Gone")
        val LengthRequired = HttpStatusCode(411, "Length Required")
        val PreconditionFailed = HttpStatusCode(412, "Precondition Failed")
        val PayloadTooLarge = HttpStatusCode(413, "Payload Too Large")
        val RequestURITooLong = HttpStatusCode(414, "Request-URI Too Long")
        val UnsupportedMediaType = HttpStatusCode(415, "Unsupported Media Type")
        val RequestedRangeNotSatisfiable = HttpStatusCode(416, "Requested Range Not Satisfiable")
        val UnprocessableEntity = HttpStatusCode(422, "Unprocessable Entity")
        val TooManyRequests = HttpStatusCode(429, "Too Many Requests")

        val InternalServerError = HttpStatusCode(500, "Internal Server Error")
        val NotImplemented = HttpStatusCode(501, "Not Implemented")
        val BadGateway = HttpStatusCode(502, "Bad Gateway")
        val ServiceUnavailable = HttpStatusCode(503, "Service Unavailable")
        val GatewayTimeout = HttpStatusCode(504, "Gateway Timeout")

        /** Creates an [HttpStatusCode] from a raw integer value, looking up its standard description. */
        fun fromValue(value: Int): HttpStatusCode {
            return when (value) {
                100 -> Continue; 101 -> SwitchingProtocols; 102 -> Processing
                200 -> OK; 201 -> Created; 202 -> Accepted; 203 -> NonAuthoritativeInformation
                204 -> NoContent; 205 -> ResetContent; 206 -> PartialContent; 207 -> MultiStatus
                300 -> MultipleChoices; 301 -> MovedPermanently; 302 -> Found; 303 -> SeeOther
                304 -> NotModified; 307 -> TemporaryRedirect; 308 -> PermanentRedirect
                400 -> BadRequest; 401 -> Unauthorized; 402 -> PaymentRequired; 403 -> Forbidden
                404 -> NotFound; 405 -> MethodNotAllowed; 406 -> NotAcceptable; 408 -> RequestTimeout
                409 -> Conflict; 410 -> Gone; 411 -> LengthRequired; 412 -> PreconditionFailed
                413 -> PayloadTooLarge; 414 -> RequestURITooLong; 415 -> UnsupportedMediaType
                416 -> RequestedRangeNotSatisfiable; 422 -> UnprocessableEntity; 429 -> TooManyRequests
                500 -> InternalServerError; 501 -> NotImplemented; 502 -> BadGateway
                503 -> ServiceUnavailable; 504 -> GatewayTimeout
                else -> HttpStatusCode(value, "Unknown")
            }
        }
    }
}
