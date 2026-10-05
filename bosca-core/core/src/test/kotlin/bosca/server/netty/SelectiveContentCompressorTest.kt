package bosca.server.netty

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SelectiveContentCompressorTest {

    @Test
    fun `identity encoding leaves text response uncompressed`() {
        val channel = EmbeddedChannel(SelectiveContentCompressor())
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/plaintext")
        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "identity")
        assertTrue(channel.writeInbound(request))

        val content = Unpooled.copiedBuffer("compressible text".repeat(128), StandardCharsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
        assertTrue(channel.writeOutbound(response))

        val encoded = channel.readOutbound<HttpResponse>()
        assertNull(encoded.headers().get(HttpHeaderNames.CONTENT_ENCODING))
        channel.finishAndReleaseAll()
    }

    @Test
    fun `partial encoded and incompressible responses bypass compression`() {
        val cases = listOf(
            Triple(HttpResponseStatus.PARTIAL_CONTENT, "text/plain", null),
            Triple(HttpResponseStatus.OK, "text/plain", "gzip"),
            Triple(HttpResponseStatus.OK, "image/png", null),
            Triple(HttpResponseStatus.OK, "video/mp4; charset=utf-8", null),
            Triple(HttpResponseStatus.OK, "audio/mpeg", null),
            Triple(HttpResponseStatus.OK, "font/woff2", null),
            Triple(HttpResponseStatus.OK, "application/zip", null),
            Triple(HttpResponseStatus.OK, "application/octet-stream", null),
            Triple(HttpResponseStatus.OK, "application/pdf", null),
        )

        cases.forEach { (status, contentType, encoding) ->
            val channel = EmbeddedChannel(SelectiveContentCompressor())
            val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/content")
            request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip")
            assertTrue(channel.writeInbound(request))
            val content = Unpooled.copiedBuffer("content".repeat(256), StandardCharsets.UTF_8)
            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content)
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType)
            encoding?.let { response.headers().set(HttpHeaderNames.CONTENT_ENCODING, it) }
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())

            assertTrue(channel.writeOutbound(response))

            val encoded = channel.readOutbound<HttpResponse>()
            assertEquals(encoding, encoded.headers().get(HttpHeaderNames.CONTENT_ENCODING))
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `malformed content type falls back to ordinary compression`() {
        val channel = EmbeddedChannel(SelectiveContentCompressor())
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/text")
        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip")
        assertTrue(channel.writeInbound(request))
        val content = Unpooled.copiedBuffer("compress me".repeat(1024), StandardCharsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "not a valid content type")
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())

        assertTrue(channel.writeOutbound(response))

        val encoded = channel.readOutbound<HttpResponse>()
        assertNotNull(encoded.headers().get(HttpHeaderNames.CONTENT_ENCODING))
        channel.finishAndReleaseAll()
    }

    @Test
    fun `response without content type uses ordinary compression`() {
        val channel = EmbeddedChannel(SelectiveContentCompressor())
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/content")
        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip")
        assertTrue(channel.writeInbound(request))
        val content = Unpooled.copiedBuffer("compress me".repeat(128), StandardCharsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())

        assertTrue(channel.writeOutbound(response))

        val encoded = channel.readOutbound<HttpResponse>()
        assertNotNull(encoded.headers().get(HttpHeaderNames.CONTENT_ENCODING))
        channel.finishAndReleaseAll()
    }

    @Test
    fun `unsupported accepted encoding leaves response uncompressed`() {
        val channel = EmbeddedChannel(SelectiveContentCompressor())
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/content")
        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "unsupported")
        assertTrue(channel.writeInbound(request))
        val content = Unpooled.copiedBuffer("content".repeat(128), StandardCharsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())

        assertTrue(channel.writeOutbound(response))

        val encoded = channel.readOutbound<HttpResponse>()
        assertNull(encoded.headers().get(HttpHeaderNames.CONTENT_ENCODING))
        channel.finishAndReleaseAll()
    }
}
