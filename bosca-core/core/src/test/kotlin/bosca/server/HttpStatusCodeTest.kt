package bosca.server

import io.netty.handler.codec.http.HttpResponseStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HttpStatusCodeTest {

    @Test
    fun `constructor stores value and description`() {
        val status = HttpStatusCode(201, "Created")
        assertEquals(201, status.value)
        assertEquals("Created", status.description)
    }

    @Test
    fun `toString returns value and description`() {
        val status = HttpStatusCode(404, "Not Found")
        assertEquals("404 Not Found", status.toString())
    }

    @Test
    fun `toNetty maps 200 OK`() {
        assertEquals(HttpResponseStatus.OK, HttpStatusCode.OK.toNetty())
    }

    @Test
    fun `toNetty maps 404 Not Found`() {
        assertEquals(HttpResponseStatus.NOT_FOUND, HttpStatusCode.NotFound.toNetty())
    }

    @Test
    fun `toNetty maps 500 Internal Server Error`() {
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, HttpStatusCode.InternalServerError.toNetty())
    }

    @Test
    fun `toNetty maps 201 Created`() {
        assertEquals(HttpResponseStatus.CREATED, HttpStatusCode.Created.toNetty())
    }

    @Test
    fun `toNetty maps 301 Moved Permanently`() {
        assertEquals(HttpResponseStatus.MOVED_PERMANENTLY, HttpStatusCode.MovedPermanently.toNetty())
    }

    @Test
    fun `toNetty maps 302 Found`() {
        assertEquals(HttpResponseStatus.FOUND, HttpStatusCode.Found.toNetty())
    }

    @Test
    fun `toNetty maps 400 Bad Request`() {
        assertEquals(HttpResponseStatus.BAD_REQUEST, HttpStatusCode.BadRequest.toNetty())
    }

    @Test
    fun `toNetty maps 401 Unauthorized`() {
        assertEquals(HttpResponseStatus.UNAUTHORIZED, HttpStatusCode.Unauthorized.toNetty())
    }

    @Test
    fun `toNetty maps 403 Forbidden`() {
        assertEquals(HttpResponseStatus.FORBIDDEN, HttpStatusCode.Forbidden.toNetty())
    }

    @Test
    fun `toNetty maps 503 Service Unavailable`() {
        assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, HttpStatusCode.ServiceUnavailable.toNetty())
    }

    @Test
    fun `companion object constants have correct values`() {
        assertEquals(100, HttpStatusCode.Continue.value)
        assertEquals(101, HttpStatusCode.SwitchingProtocols.value)
        assertEquals(102, HttpStatusCode.Processing.value)
        assertEquals(200, HttpStatusCode.OK.value)
        assertEquals(201, HttpStatusCode.Created.value)
        assertEquals(202, HttpStatusCode.Accepted.value)
        assertEquals(203, HttpStatusCode.NonAuthoritativeInformation.value)
        assertEquals(204, HttpStatusCode.NoContent.value)
        assertEquals(205, HttpStatusCode.ResetContent.value)
        assertEquals(206, HttpStatusCode.PartialContent.value)
        assertEquals(207, HttpStatusCode.MultiStatus.value)
        assertEquals(300, HttpStatusCode.MultipleChoices.value)
        assertEquals(301, HttpStatusCode.MovedPermanently.value)
        assertEquals(302, HttpStatusCode.Found.value)
        assertEquals(303, HttpStatusCode.SeeOther.value)
        assertEquals(304, HttpStatusCode.NotModified.value)
        assertEquals(307, HttpStatusCode.TemporaryRedirect.value)
        assertEquals(308, HttpStatusCode.PermanentRedirect.value)
        assertEquals(400, HttpStatusCode.BadRequest.value)
        assertEquals(401, HttpStatusCode.Unauthorized.value)
        assertEquals(402, HttpStatusCode.PaymentRequired.value)
        assertEquals(403, HttpStatusCode.Forbidden.value)
        assertEquals(404, HttpStatusCode.NotFound.value)
        assertEquals(405, HttpStatusCode.MethodNotAllowed.value)
        assertEquals(406, HttpStatusCode.NotAcceptable.value)
        assertEquals(408, HttpStatusCode.RequestTimeout.value)
        assertEquals(409, HttpStatusCode.Conflict.value)
        assertEquals(410, HttpStatusCode.Gone.value)
        assertEquals(411, HttpStatusCode.LengthRequired.value)
        assertEquals(412, HttpStatusCode.PreconditionFailed.value)
        assertEquals(413, HttpStatusCode.PayloadTooLarge.value)
        assertEquals(414, HttpStatusCode.RequestURITooLong.value)
        assertEquals(415, HttpStatusCode.UnsupportedMediaType.value)
        assertEquals(416, HttpStatusCode.RequestedRangeNotSatisfiable.value)
        assertEquals(422, HttpStatusCode.UnprocessableEntity.value)
        assertEquals(429, HttpStatusCode.TooManyRequests.value)
        assertEquals(500, HttpStatusCode.InternalServerError.value)
        assertEquals(501, HttpStatusCode.NotImplemented.value)
        assertEquals(502, HttpStatusCode.BadGateway.value)
        assertEquals(503, HttpStatusCode.ServiceUnavailable.value)
        assertEquals(504, HttpStatusCode.GatewayTimeout.value)
    }

    @Test
    fun `companion object constants have correct descriptions`() {
        assertEquals("OK", HttpStatusCode.OK.description)
        assertEquals("Not Found", HttpStatusCode.NotFound.description)
        assertEquals("Internal Server Error", HttpStatusCode.InternalServerError.description)
        assertEquals("Bad Request", HttpStatusCode.BadRequest.description)
        assertEquals("Unauthorized", HttpStatusCode.Unauthorized.description)
    }

    @Test
    fun `fromValue returns all known status code instances`() {
        // 1xx
        assertEquals(HttpStatusCode.Continue, HttpStatusCode.fromValue(100))
        assertEquals(HttpStatusCode.SwitchingProtocols, HttpStatusCode.fromValue(101))
        assertEquals(HttpStatusCode.Processing, HttpStatusCode.fromValue(102))
        // 2xx
        assertEquals(HttpStatusCode.OK, HttpStatusCode.fromValue(200))
        assertEquals(HttpStatusCode.Created, HttpStatusCode.fromValue(201))
        assertEquals(HttpStatusCode.Accepted, HttpStatusCode.fromValue(202))
        assertEquals(HttpStatusCode.NonAuthoritativeInformation, HttpStatusCode.fromValue(203))
        assertEquals(HttpStatusCode.NoContent, HttpStatusCode.fromValue(204))
        assertEquals(HttpStatusCode.ResetContent, HttpStatusCode.fromValue(205))
        assertEquals(HttpStatusCode.PartialContent, HttpStatusCode.fromValue(206))
        assertEquals(HttpStatusCode.MultiStatus, HttpStatusCode.fromValue(207))
        // 3xx
        assertEquals(HttpStatusCode.MultipleChoices, HttpStatusCode.fromValue(300))
        assertEquals(HttpStatusCode.MovedPermanently, HttpStatusCode.fromValue(301))
        assertEquals(HttpStatusCode.Found, HttpStatusCode.fromValue(302))
        assertEquals(HttpStatusCode.SeeOther, HttpStatusCode.fromValue(303))
        assertEquals(HttpStatusCode.NotModified, HttpStatusCode.fromValue(304))
        assertEquals(HttpStatusCode.TemporaryRedirect, HttpStatusCode.fromValue(307))
        assertEquals(HttpStatusCode.PermanentRedirect, HttpStatusCode.fromValue(308))
        // 4xx
        assertEquals(HttpStatusCode.BadRequest, HttpStatusCode.fromValue(400))
        assertEquals(HttpStatusCode.Unauthorized, HttpStatusCode.fromValue(401))
        assertEquals(HttpStatusCode.PaymentRequired, HttpStatusCode.fromValue(402))
        assertEquals(HttpStatusCode.Forbidden, HttpStatusCode.fromValue(403))
        assertEquals(HttpStatusCode.NotFound, HttpStatusCode.fromValue(404))
        assertEquals(HttpStatusCode.MethodNotAllowed, HttpStatusCode.fromValue(405))
        assertEquals(HttpStatusCode.NotAcceptable, HttpStatusCode.fromValue(406))
        assertEquals(HttpStatusCode.RequestTimeout, HttpStatusCode.fromValue(408))
        assertEquals(HttpStatusCode.Conflict, HttpStatusCode.fromValue(409))
        assertEquals(HttpStatusCode.Gone, HttpStatusCode.fromValue(410))
        assertEquals(HttpStatusCode.LengthRequired, HttpStatusCode.fromValue(411))
        assertEquals(HttpStatusCode.PreconditionFailed, HttpStatusCode.fromValue(412))
        assertEquals(HttpStatusCode.PayloadTooLarge, HttpStatusCode.fromValue(413))
        assertEquals(HttpStatusCode.RequestURITooLong, HttpStatusCode.fromValue(414))
        assertEquals(HttpStatusCode.UnsupportedMediaType, HttpStatusCode.fromValue(415))
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, HttpStatusCode.fromValue(416))
        assertEquals(HttpStatusCode.UnprocessableEntity, HttpStatusCode.fromValue(422))
        assertEquals(HttpStatusCode.TooManyRequests, HttpStatusCode.fromValue(429))
        // 5xx
        assertEquals(HttpStatusCode.InternalServerError, HttpStatusCode.fromValue(500))
        assertEquals(HttpStatusCode.NotImplemented, HttpStatusCode.fromValue(501))
        assertEquals(HttpStatusCode.BadGateway, HttpStatusCode.fromValue(502))
        assertEquals(HttpStatusCode.ServiceUnavailable, HttpStatusCode.fromValue(503))
        assertEquals(HttpStatusCode.GatewayTimeout, HttpStatusCode.fromValue(504))
    }

    @Test
    fun `fromValue returns Unknown for unrecognized status code`() {
        val status = HttpStatusCode.fromValue(999)
        assertEquals(999, status.value)
        assertEquals("Unknown", status.description)
    }

    @Test
    fun `data class equality works correctly`() {
        val a = HttpStatusCode(200, "OK")
        val b = HttpStatusCode(200, "OK")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality for different values`() {
        assertNotEquals(HttpStatusCode.OK, HttpStatusCode.NotFound)
    }

    @Test
    fun `custom status code with toNetty`() {
        val custom = HttpStatusCode(418, "I'm a Teapot")
        val netty = custom.toNetty()
        assertEquals(418, netty.code())
    }
}
