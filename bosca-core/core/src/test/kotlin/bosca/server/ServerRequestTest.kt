package bosca.server

import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpMethod as NettyHttpMethod
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRequestTest {

    private fun createMockRequest(
        uri: String = "/test",
        method: NettyHttpMethod = NettyHttpMethod.GET,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
        remoteAddress: String? = null,
        trustForwardedHeaders: Boolean = true,
    ): ServerRequest {
        val nettyHeaders = DefaultHttpHeaders()
        headers.forEach { (k, v) -> nettyHeaders.add(k, v) }

        val request = mockk<HttpRequest>()
        every { request.uri() } returns uri
        every { request.method() } returns method
        every { request.headers() } returns nettyHeaders

        val requestBody = if (body.isNotEmpty()) {
            RequestBody().also { rb ->
                val content = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8)
                rb.addContent(DefaultLastHttpContent(content))
            }
        } else {
            null
        }

        return ServerRequest(request, requestBody, remoteAddress, trustForwardedHeaders)
    }

    @Test
    fun `path extracts path without query string`() {
        val request = createMockRequest(uri = "/users?page=1&size=10")
        assertEquals("/users", request.path)
    }

    @Test
    fun `path returns full uri when no query string`() {
        val request = createMockRequest(uri = "/users/123")
        assertEquals("/users/123", request.path)
    }

    @Test
    fun `uri returns full uri including query`() {
        val request = createMockRequest(uri = "/users?page=1")
        assertEquals("/users?page=1", request.uri)
    }

    @Test
    fun `queryParameters parses single parameter`() {
        val request = createMockRequest(uri = "/search?q=hello")
        assertEquals("hello", request.queryParameters["q"])
    }

    @Test
    fun `queryParameters parses multiple parameters`() {
        val request = createMockRequest(uri = "/search?q=hello&page=2&size=10")
        assertEquals("hello", request.queryParameters["q"])
        assertEquals("2", request.queryParameters["page"])
        assertEquals("10", request.queryParameters["size"])
    }

    @Test
    fun `queryParameters returns empty for no query string`() {
        val request = createMockRequest(uri = "/users")
        assertTrue(request.queryParameters.isEmpty)
    }

    @Test
    fun `httpMethod parses GET`() {
        val request = createMockRequest(method = NettyHttpMethod.GET)
        assertEquals(HttpMethod.Get, request.httpMethod)
    }

    @Test
    fun `httpMethod parses POST`() {
        val request = createMockRequest(method = NettyHttpMethod.POST)
        assertEquals(HttpMethod.Post, request.httpMethod)
    }

    @Test
    fun `contentType parses from header`() {
        val request = createMockRequest(headers = mapOf("Content-Type" to "application/json"))
        val ct = request.contentType()
        assertNotNull(ct)
        assertEquals("application", ct.contentType)
        assertEquals("json", ct.contentSubtype)
    }

    @Test
    fun `contentType returns null when header missing`() {
        val request = createMockRequest()
        assertNull(request.contentType())
    }

    @Test
    fun `contentType parses with charset parameter`() {
        val request = createMockRequest(headers = mapOf("Content-Type" to "text/html; charset=utf-8"))
        val ct = request.contentType()
        assertNotNull(ct)
        assertEquals("text", ct.contentType)
        assertEquals("html", ct.contentSubtype)
        assertEquals("utf-8", ct.parameters["charset"])
    }

    @Test
    fun `acceptLanguageItems parses single language`() {
        val request = createMockRequest(headers = mapOf("Accept-Language" to "en-US"))
        val items = request.acceptLanguageItems()
        assertEquals(1, items.size)
        assertEquals("en-US", items[0].value)
        assertEquals(1.0f, items[0].quality)
    }

    @Test
    fun `acceptLanguageItems parses multiple with quality factors`() {
        val request = createMockRequest(
            headers = mapOf("Accept-Language" to "en-US,fr;q=0.8,de;q=0.5")
        )
        val items = request.acceptLanguageItems()
        assertEquals(3, items.size)
        assertEquals("en-US", items[0].value)
        assertEquals(1.0f, items[0].quality)
        assertEquals("fr", items[1].value)
        assertEquals(0.8f, items[1].quality)
        assertEquals("de", items[2].value)
        assertEquals(0.5f, items[2].quality)
    }

    @Test
    fun `acceptLanguageItems sorts by quality descending`() {
        val request = createMockRequest(
            headers = mapOf("Accept-Language" to "de;q=0.5,fr;q=0.9,en-US")
        )
        val items = request.acceptLanguageItems()
        assertEquals("en-US", items[0].value)
        assertEquals("fr", items[1].value)
        assertEquals("de", items[2].value)
    }

    @Test
    fun `acceptLanguageItems returns empty when header missing`() {
        val request = createMockRequest()
        assertTrue(request.acceptLanguageItems().isEmpty())
    }

    @Test
    fun `acceptLanguageItems defaults malformed quality to one`() {
        val request = createMockRequest(headers = mapOf("Accept-Language" to "en;q=invalid"))
        assertEquals(1.0f, request.acceptLanguageItems().single().quality)
    }

    @Test
    fun `receiveFormParameters parses URL-encoded body`() = runTest {
        val request = createMockRequest(
            body = "username=john&password=secret",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded")
        )
        val params = request.receiveFormParameters()
        assertEquals("john", params["username"])
        assertEquals("secret", params["password"])
    }

    @Test
    fun `receiveFormParameters URL-decodes values`() = runTest {
        val request = createMockRequest(
            body = "message=hello+world&path=%2Fapi%2Ftest"
        )
        val params = request.receiveFormParameters()
        assertEquals("hello world", params["message"])
        assertEquals("/api/test", params["path"])
    }

    @Test
    fun `receiveFormParameters returns empty for blank body`() = runTest {
        val request = createMockRequest(body = "")
        val params = request.receiveFormParameters()
        assertTrue(params.isEmpty)
    }

    @Test
    fun `origin uses forwarded headers when present`() {
        val request = createMockRequest(
            headers = mapOf(
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-Host" to "example.com",
                "X-Forwarded-Port" to "8443"
            )
        )
        val origin = request.origin
        assertEquals("https", origin.scheme)
        assertEquals("example.com", origin.host)
        assertEquals(8443, origin.port)
    }

    @Test
    fun `origin falls back to Host header`() {
        val request = createMockRequest(
            headers = mapOf("Host" to "myhost.com")
        )
        val origin = request.origin
        assertEquals("http", origin.scheme)
        assertEquals("myhost.com", origin.host)
        assertEquals(80, origin.port)
    }

    @Test
    fun `origin defaults to localhost when no headers`() {
        val request = createMockRequest()
        val origin = request.origin
        assertEquals("http", origin.scheme)
        assertEquals("localhost", origin.host)
        assertEquals(80, origin.port)
    }

    @Test
    fun `origin defaults to port 443 for https`() {
        val request = createMockRequest(
            headers = mapOf("X-Forwarded-Proto" to "https")
        )
        val origin = request.origin
        assertEquals("https", origin.scheme)
        assertEquals(443, origin.port)
    }

    @Test
    fun `appOrigin prefers the browser Origin header over the addressed forwarded host`() {
        // The Studio host the user is on (Origin) must win over the API host the request was proxied to
        // (X-Forwarded-Host) — otherwise an auth-email link points at the API, not the app.
        val request = createMockRequest(
            headers = mapOf(
                "Origin" to "https://studio.example.com",
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-Host" to "example.com"
            )
        )
        assertEquals("https://studio.example.com", request.appOrigin)
    }

    @Test
    fun `appOrigin falls back to the Referer host when Origin is absent`() {
        val request = createMockRequest(
            headers = mapOf(
                "Referer" to "https://studio.example.com/auth/login?next=%2Fhome",
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-Host" to "example.com"
            )
        )
        // The full Referer URL is reduced to its origin (scheme://host), dropping path and query.
        assertEquals("https://studio.example.com", request.appOrigin)
    }

    @Test
    fun `appOrigin treats the literal null Origin as absent and falls back`() {
        val request = createMockRequest(
            headers = mapOf(
                "Origin" to "null",
                "Referer" to "https://studio.example.com/",
                "X-Forwarded-Host" to "example.com"
            )
        )
        assertEquals("https://studio.example.com", request.appOrigin)
    }

    @Test
    fun `appOrigin falls back to the addressed origin when neither Origin nor Referer is present`() {
        // Non-browser callers (native apps, server-to-server) send neither header: behavior is unchanged.
        val request = createMockRequest(
            headers = mapOf(
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-Host" to "example.com"
            )
        )
        assertEquals("https://example.com", request.appOrigin)
    }

    @Test
    fun `appOrigin preserves a non-default port from the Origin header`() {
        val request = createMockRequest(
            headers = mapOf("Origin" to "http://localhost:3000")
        )
        assertEquals("http://localhost:3000", request.appOrigin)
    }

    @Test
    fun `appOrigin ignores a malformed Origin and falls back to the addressed origin`() {
        val request = createMockRequest(
            headers = mapOf(
                "Origin" to "::not a url::",
                "Host" to "myhost.com"
            )
        )
        assertEquals("http://myhost.com", request.appOrigin)
    }

    @Test
    fun `bodyText returns UTF-8 decoded body`() = runTest {
        val request = createMockRequest(body = "Hello, World!")
        assertEquals("Hello, World!", request.bodyText())
    }

    @Test
    fun `bodyBytes returns raw bytes`() = runTest {
        val request = createMockRequest(body = "abc")
        val bytes = request.bodyBytes()
        assertEquals(3, bytes.size)
        assertEquals('a'.code.toByte(), bytes[0])
        assertEquals('b'.code.toByte(), bytes[1])
        assertEquals('c'.code.toByte(), bytes[2])
    }

    @Test
    fun `bodyBytes returns empty for bodyless request`() = runTest {
        val request = createMockRequest()
        val bytes = request.bodyBytes()
        assertEquals(0, bytes.size)
    }

    @Test
    fun `bodyText returns empty for bodyless request`() = runTest {
        val request = createMockRequest()
        assertEquals("", request.bodyText())
    }

    @Test
    fun `bodyStreamTo copies a body without accumulating it`() = runTest {
        val request = createMockRequest(body = "streamed")
        val output = ByteArrayOutputStream()

        val copied = request.bodyStreamTo(output)

        assertEquals(8, copied)
        assertEquals("streamed", output.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun `bodyStreamTo returns zero for a bodyless request`() = runTest {
        assertEquals(0, createMockRequest().bodyStreamTo(ByteArrayOutputStream()))
    }

    @Test
    fun `request body can only be consumed once`() = runTest {
        val request = createMockRequest(body = "once")
        assertEquals("once", request.bodyText())
        assertFailsWith<IllegalStateException> { request.bodyBytes() }
    }

    // --- client IP resolution ---

    @Test
    fun `clientIp uses the first forwarded address and caches it`() {
        val request = createMockRequest(
            headers = mapOf(HttpHeaders.XForwardedFor to "203.0.113.5, 10.0.0.1"),
            remoteAddress = "127.0.0.1",
        )

        assertEquals("203.0.113.5", request.clientIp)
        assertEquals("203.0.113.5", request.clientIp)
    }

    @Test
    fun `clientIp uses real IP when forwarded-for is absent`() {
        val request = createMockRequest(
            headers = mapOf(HttpHeaders.XRealIp to "198.51.100.7"),
            remoteAddress = "127.0.0.1",
        )
        assertEquals("198.51.100.7", request.clientIp)
    }

    @Test
    fun `clientIp ignores forwarded headers when they are untrusted`() {
        val request = createMockRequest(
            headers = mapOf(HttpHeaders.XForwardedFor to "203.0.113.5"),
            remoteAddress = "127.0.0.1",
            trustForwardedHeaders = false,
        )
        assertEquals("127.0.0.1", request.clientIp)
    }

    @Test
    fun `clientIp can resolve to null`() {
        val request = createMockRequest()
        assertNull(request.clientIp)
        assertNull(request.clientIp)
    }

    // --- origin edge cases ---

    @Test
    fun `origin parses a port from the Host header`() {
        val origin = createMockRequest(headers = mapOf(HttpHeaders.Host to "example.test:8080")).origin
        assertEquals(RequestOrigin("http", "example.test", 8080), origin)
    }

    @Test
    fun `origin retains a host whose port is malformed`() {
        val origin = createMockRequest(headers = mapOf(HttpHeaders.Host to "example.test:bad")).origin
        assertEquals(RequestOrigin("http", "example.test:bad", 80), origin)
    }

    @Test
    fun `origin parses bracketed IPv6 with and without a port`() {
        assertEquals(
            RequestOrigin("http", "2001:db8::1", 8080),
            createMockRequest(headers = mapOf(HttpHeaders.Host to "[2001:db8::1]:8080")).origin,
        )
        assertEquals(
            RequestOrigin("http", "2001:db8::1", 80),
            createMockRequest(headers = mapOf(HttpHeaders.Host to "[2001:db8::1]")).origin,
        )
    }

    @Test
    fun `origin tolerates malformed bracketed IPv6`() {
        val origin = createMockRequest(headers = mapOf(HttpHeaders.Host to "[2001:db8::1")).origin
        assertEquals(RequestOrigin("http", "[2001:db8::1", 80), origin)
    }

    @Test
    fun `origin ignores all forwarded values when untrusted`() {
        val request = createMockRequest(
            headers = mapOf(
                HttpHeaders.XForwardedProto to "https",
                HttpHeaders.XForwardedHost to "forwarded.test",
                HttpHeaders.XForwardedPort to "9443",
                HttpHeaders.Host to "direct.test:8080",
            ),
            trustForwardedHeaders = false,
        )
        assertEquals(RequestOrigin("http", "direct.test", 8080), request.origin)
    }

    @Test
    fun `RequestOrigin omits default ports and retains custom ports`() {
        assertEquals("HTTPS://secure.test", RequestOrigin("HTTPS", "secure.test", 443).toOrigin())
        assertEquals("http://plain.test", RequestOrigin("http", "plain.test", 80).toOrigin())
        assertEquals("https://secure.test:8443", RequestOrigin("https", "secure.test", 8443).toOrigin())
    }

    @Test
    fun `appOrigin omits browser default ports and rejects unusable candidates`() {
        assertEquals(
            "https://studio.test",
            createMockRequest(headers = mapOf(HttpHeaders.Origin to "https://studio.test:443")).appOrigin,
        )
        assertEquals(
            "http://studio.test",
            createMockRequest(headers = mapOf(HttpHeaders.Origin to "http://studio.test:80")).appOrigin,
        )
        assertEquals(
            "http://fallback.test",
            createMockRequest(headers = mapOf(
                HttpHeaders.Origin to "mailto:user@example.test",
                HttpHeaders.Referer to "not a URI",
                HttpHeaders.Host to "fallback.test",
            )).appOrigin,
        )
    }

    @Test
    fun `request derived values cache and tolerate malformed edge inputs`() {
        val cached = createMockRequest(
            uri = "/cached?one=1",
            headers = mapOf(
                HttpHeaders.Host to "cached.test:8080",
                HttpHeaders.Origin to "https://studio.test",
                HttpHeaders.Cookie to "one=1",
            ),
        )
        repeat(2) {
            assertEquals(HttpMethod.Get, cached.httpMethod)
            assertEquals("/cached", cached.path)
            assertEquals("1", cached.queryParameters["one"])
            assertEquals("1", cached.cookies["one"])
            assertEquals(RequestOrigin("http", "cached.test", 8080), cached.origin)
            assertEquals("https://studio.test", cached.appOrigin)
        }

        assertEquals(
            RequestOrigin("http", "edge.test:", 80),
            createMockRequest(headers = mapOf(HttpHeaders.Host to "edge.test:")).origin,
        )
        assertEquals(
            RequestOrigin("http", ":8080", 80),
            createMockRequest(headers = mapOf(HttpHeaders.Host to ":8080")).origin,
        )
        assertEquals(
            RequestOrigin("http", "2001:db8::1", 80),
            createMockRequest(headers = mapOf(HttpHeaders.Host to "[2001:db8::1]:bad")).origin,
        )
        assertEquals(
            RequestOrigin("http", "2001:db8::1", 80),
            createMockRequest(headers = mapOf(HttpHeaders.Host to "[2001:db8::1]suffix")).origin,
        )
        assertEquals(
            RequestOrigin("https", "forwarded.test", 443),
            createMockRequest(headers = mapOf(
                HttpHeaders.XForwardedProto to "https",
                HttpHeaders.XForwardedHost to "forwarded.test",
                HttpHeaders.XForwardedPort to "invalid",
            )).origin,
        )
        assertEquals(
            "http://fallback.test",
            createMockRequest(headers = mapOf(
                HttpHeaders.Origin to "",
                HttpHeaders.Referer to "//studio.test/path",
                HttpHeaders.Host to "fallback.test",
            )).appOrigin,
        )
        assertNull(
            createMockRequest(
                headers = mapOf(HttpHeaders.XForwardedFor to ",proxy", HttpHeaders.XRealIp to ""),
                remoteAddress = "127.0.0.1",
            ).clientIp,
        )
    }

    @Test
    fun `header returns header value`() {
        val request = createMockRequest(headers = mapOf("X-Custom" to "test"))
        assertEquals("test", request.header("X-Custom"))
    }

    @Test
    fun `header returns null for missing header`() {
        val request = createMockRequest()
        assertNull(request.header("X-Missing"))
    }

    @Test
    fun `cookies parsed from Cookie header`() {
        val request = createMockRequest(headers = mapOf("Cookie" to "session=abc; user=john"))
        assertEquals("abc", request.cookies["session"])
        assertEquals("john", request.cookies["user"])
    }

    @Test
    fun `acceptLanguage returns raw header value`() {
        val request = createMockRequest(headers = mapOf("Accept-Language" to "en-US,fr"))
        assertEquals("en-US,fr", request.acceptLanguage())
    }

    @Test
    fun `acceptLanguage returns null when not present`() {
        val request = createMockRequest()
        assertNull(request.acceptLanguage())
    }

    // --- receiveFormParameters edge cases ---

    @Test
    fun `receiveFormParameters treats valueless keys as empty string per HTML spec`() = runTest {
        val request = createMockRequest(body = "good=value&bad&also=ok")
        val params = request.receiveFormParameters()
        assertEquals("value", params["good"])
        assertEquals("ok", params["also"])
        assertEquals("", params["bad"])
    }

    // --- receiveMultipart tests ---

    @Test
    fun `receiveMultipart with form fields`() = runTest {
        val boundary = "----TestBoundary"
        val multipartBody = buildString {
            append("------TestBoundary\r\n")
            append("Content-Disposition: form-data; name=\"field1\"\r\n\r\n")
            append("value1\r\n")
            append("------TestBoundary\r\n")
            append("Content-Disposition: form-data; name=\"field2\"\r\n\r\n")
            append("value2\r\n")
            append("------TestBoundary--\r\n")
        }

        val nettyHeaders = DefaultHttpHeaders()
        nettyHeaders.add("Content-Type", "multipart/form-data; boundary=----TestBoundary")
        nettyHeaders.add("Content-Length", multipartBody.length.toString())

        val request = mockk<HttpRequest>()
        every { request.uri() } returns "/upload"
        every { request.method() } returns NettyHttpMethod.POST
        every { request.headers() } returns nettyHeaders
        every { request.protocolVersion() } returns io.netty.handler.codec.http.HttpVersion.HTTP_1_1

        val requestBody = RequestBody()
        val content = Unpooled.copiedBuffer(multipartBody, StandardCharsets.UTF_8)
        requestBody.addContent(DefaultLastHttpContent(content))

        val serverRequest = ServerRequest(request, requestBody)
        val multipart = serverRequest.receiveMultipart()

        assertTrue(multipart.parts.isNotEmpty())
        multipart.close()
    }

    @Test
    fun `receiveMultipart without a request body returns no parts`() = runTest {
        val nettyHeaders = DefaultHttpHeaders()
        nettyHeaders.add("Content-Type", "multipart/form-data; boundary=empty")
        nettyHeaders.add("Content-Length", "0")
        val request = mockk<HttpRequest>()
        every { request.uri() } returns "/upload"
        every { request.method() } returns NettyHttpMethod.POST
        every { request.headers() } returns nettyHeaders
        every { request.protocolVersion() } returns io.netty.handler.codec.http.HttpVersion.HTTP_1_1

        val multipart = ServerRequest(request, null).receiveMultipart()

        assertTrue(multipart.parts.isEmpty())
    }

    @Test
    fun `receiveMultipart exposes uploaded files`() = runTest {
        val boundary = "FileBoundary"
        val multipartBody = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"upload\"; filename=\"test.txt\"\r\n")
            append("Content-Type: text/plain\r\n\r\n")
            append("file contents\r\n")
            append("--$boundary--\r\n")
        }
        val nettyHeaders = DefaultHttpHeaders()
        nettyHeaders.add("Content-Type", "multipart/form-data; boundary=$boundary")
        nettyHeaders.add("Content-Length", multipartBody.toByteArray().size.toString())
        val request = mockk<HttpRequest>()
        every { request.uri() } returns "/upload"
        every { request.method() } returns NettyHttpMethod.POST
        every { request.headers() } returns nettyHeaders
        every { request.protocolVersion() } returns io.netty.handler.codec.http.HttpVersion.HTTP_1_1
        val body = RequestBody().also {
            it.addContent(DefaultLastHttpContent(Unpooled.copiedBuffer(multipartBody, StandardCharsets.UTF_8)))
        }

        val multipart = ServerRequest(request, body).receiveMultipart()
        val file = multipart.parts.single() as bosca.server.content.PartData.FileUploadItem

        assertEquals("upload", file.name)
        assertEquals("test.txt", file.originalFileName)
        assertEquals("file contents", file.streamProvider.bufferedReader().readText())
        multipart.close()
    }

    // --- RequestHeaders tests ---

    @Test
    fun `headers getAll returns all values for a header`() {
        val nettyHeaders = DefaultHttpHeaders()
        nettyHeaders.add("X-Multi", "val1")
        nettyHeaders.add("X-Multi", "val2")
        nettyHeaders.add("X-Multi", "val3")
        val headers = RequestHeaders(nettyHeaders)
        assertEquals(listOf("val1", "val2", "val3"), headers.getAll("X-Multi"))
    }

    @Test
    fun `headers getAll returns empty list for missing header`() {
        val nettyHeaders = DefaultHttpHeaders()
        val headers = RequestHeaders(nettyHeaders)
        assertEquals(emptyList(), headers.getAll("X-Missing"))
    }

    @Test
    fun `headers names returns all header names`() {
        val nettyHeaders = DefaultHttpHeaders()
        nettyHeaders.add("Content-Type", "text/plain")
        nettyHeaders.add("Accept", "application/json")
        val headers = RequestHeaders(nettyHeaders)
        val names = headers.names()
        assertTrue(names.contains("Content-Type") || names.contains("content-type"))
        assertTrue(names.contains("Accept") || names.contains("accept"))
    }

    @Test
    fun `headers isEmpty returns true for empty headers`() {
        val nettyHeaders = DefaultHttpHeaders()
        val headers = RequestHeaders(nettyHeaders)
        assertTrue(headers.isEmpty())
    }

    @Test
    fun `headers isEmpty returns false when headers present`() {
        val nettyHeaders = DefaultHttpHeaders()
        nettyHeaders.add("Host", "localhost")
        val headers = RequestHeaders(nettyHeaders)
        assertFalse(headers.isEmpty())
    }

    @Test
    fun `headers contains returns true for existing header`() {
        val nettyHeaders = DefaultHttpHeaders()
        nettyHeaders.add("Authorization", "Bearer token")
        val headers = RequestHeaders(nettyHeaders)
        assertTrue("Authorization" in headers)
    }

    @Test
    fun `headers contains returns false for missing header`() {
        val nettyHeaders = DefaultHttpHeaders()
        val headers = RequestHeaders(nettyHeaders)
        assertFalse("Authorization" in headers)
    }

    @Test
    fun `headers forEach iterates all headers`() {
        val nettyHeaders = DefaultHttpHeaders()
        nettyHeaders.add("A", "1")
        nettyHeaders.add("B", "2")
        val headers = RequestHeaders(nettyHeaders)
        val collected = mutableMapOf<String, List<String>>()
        headers.forEach { name, values ->
            collected[name] = values
        }
        assertEquals(2, collected.size)
    }

    @Test
    fun `headers entries returns header entries`() {
        val nettyHeaders = DefaultHttpHeaders()
        nettyHeaders.add("X-One", "1")
        nettyHeaders.add("X-Two", "2")
        val headers = RequestHeaders(nettyHeaders)
        val entries = headers.entries()
        assertEquals(2, entries.size)
    }
}
