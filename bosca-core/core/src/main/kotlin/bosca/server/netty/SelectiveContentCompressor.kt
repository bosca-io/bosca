package bosca.server.netty

import bosca.server.ContentType
import io.netty.handler.codec.http.HttpContentCompressor
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponse

/**
 * A content compressor that skips compression for responses where compressing
 * would be harmful or wasteful:
 *
 * - **HTTP 206 Partial Content**: The Content-Range header references raw byte offsets;
 *   compressing the body invalidates those positions and breaks seeking.
 * - **Already-encoded responses**: If Content-Encoding is already set (e.g., `identity`,
 *   `gzip`), double-compressing wastes CPU and may corrupt the stream.
 * - **Binary/pre-compressed content types**: Images, video, audio, archives, and fonts
 *   are already compressed and gain negligible benefit from further encoding.
 */
open class SelectiveContentCompressor : HttpContentCompressor() {

    override fun beginEncode(httpResponse: HttpResponse, acceptEncoding: String): Result? {
        if (!willEncode(httpResponse, acceptEncoding)) return null
        return super.beginEncode(httpResponse, acceptEncoding)
    }

    /** Returns whether this response and accepted-encoding value will create a content encoder. */
    internal fun willEncode(httpResponse: HttpResponse, acceptEncoding: String): Boolean {
        if (acceptEncoding.equals("identity", ignoreCase = true)) return false
        if (!isCompressionCandidate(httpResponse)) return false

        return determineEncoding(acceptEncoding) != null
    }

    /** Returns whether content negotiation could select an encoded representation for this response. */
    internal fun isCompressionCandidate(httpResponse: HttpResponse): Boolean {
        if (httpResponse.status().code() == 206) return false

        val contentEncoding = httpResponse.headers().get(HttpHeaderNames.CONTENT_ENCODING)
        if (contentEncoding != null) return false

        val contentType = httpResponse.headers().get(HttpHeaderNames.CONTENT_TYPE)
        if (contentType != null && isIncompressibleContentType(contentType)) return false

        return true
    }

    companion object {
        /** Top-level types where all subtypes are incompressible. */
        private val INCOMPRESSIBLE_TYPES = setOf("image", "video", "audio", "font")

        /** Specific content types that are incompressible. */
        private val INCOMPRESSIBLE_CONTENT_TYPES = setOf(
            ContentType("application", "zip"),
            ContentType("application", "gzip"),
            ContentType("application", "x-gzip"),
            ContentType("application", "x-bzip2"),
            ContentType("application", "x-xz"),
            ContentType("application", "zstd"),
            ContentType("application", "x-7z-compressed"),
            ContentType("application", "x-rar-compressed"),
            ContentType.Application.OctetStream,
            ContentType("application", "pdf"),
            ContentType.Application.ProtoBuf,
            ContentType("application", "x-protobuf"),
            ContentType("application", "grpc"),
            ContentType("application", "font"),
            ContentType("application", "x-font"),
        )

        private fun isIncompressibleContentType(raw: String): Boolean {
            val ct = try {
                ContentType.parse(raw)
            } catch (_: IllegalArgumentException) {
                return false
            }
            return ct.contentType in INCOMPRESSIBLE_TYPES || ct.withoutParameters() in INCOMPRESSIBLE_CONTENT_TYPES
        }
    }
}
