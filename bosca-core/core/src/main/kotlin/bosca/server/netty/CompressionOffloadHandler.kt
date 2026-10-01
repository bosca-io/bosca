package bosca.server.netty

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufHolder
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelPromise
import io.netty.channel.PendingWriteQueue
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpStatusClass
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.codec.http2.DefaultHttp2DataFrame
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.EventExecutor
import io.netty.util.concurrent.EventExecutorGroup
import java.util.ArrayDeque

/**
 * Keeps ordinary HTTP traffic on the channel event loop and offloads only response compression.
 *
 * Netty's [io.netty.handler.codec.http.HttpContentCompressor] combines request negotiation,
 * response ordering, and compression in one handler. Binding that handler to another executor
 * therefore moves every request and every response across executors, including responses that are
 * not compressed. This handler retains the same negotiation and streaming behavior while keeping
 * its request queue and pass-through path on the channel event loop. Only creation and use of the
 * per-response [EmbeddedChannel] encoder is submitted to [compressionExecutor].
 *
 * Compressed writes are delivered back to the channel event loop in submission order. If a later
 * pass-through response arrives while compression is pending, it joins the same ordered queue so it
 * cannot overtake the encoded response.
 */
internal class CompressionOffloadHandler(
    private val compressionExecutor: EventExecutor,
) : SelectiveContentCompressor() {

    private data class RequestEncoding(
        val method: HttpMethod,
        val acceptEncoding: String,
    )

    private val requests = ArrayDeque<RequestEncoding>()

    // Accessed only on the channel event loop.
    private var responseCompressionActive = false
    private var pendingOrderedWrites = 0
    private var pendingWriteAccountingActive = true
    private lateinit var pendingWriteAccounting: PendingWriteQueue

    // Created, used, and released only on compressionExecutor.
    private var streamingEncoder: EmbeddedChannel? = null

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        pendingWriteAccounting = PendingWriteQueue(ctx)
        super.handlerAdded(ctx)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is HttpRequest) {
            requests.addLast(
                RequestEncoding(
                    method = msg.method(),
                    acceptEncoding = acceptedEncoding(msg),
                ),
            )
        }
        ctx.fireChannelRead(msg)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        when (msg) {
            is HttpResponse -> {
                val acceptEncoding = compressionEncoding(msg)
                if (acceptEncoding != null) {
                    responseCompressionActive = msg !is LastHttpContent
                    if (msg is FullHttpResponse) {
                        submitOrdered(ctx, msg, promise) {
                            encodeFullResponse(msg, acceptEncoding)
                        }
                    } else {
                        submitOrdered(ctx, msg, promise) {
                            beginStreamingResponse(msg, acceptEncoding)
                        }
                    }
                    return
                }
                responseCompressionActive = false
            }

            is HttpContent -> {
                if (responseCompressionActive) {
                    if (msg is LastHttpContent) responseCompressionActive = false
                    submitOrdered(ctx, msg, promise) {
                        encodeStreamingContent(msg)
                    }
                    return
                }
            }

            else -> {
                ctx.write(msg, promise)
                return
            }
        }

        if (pendingOrderedWrites == 0) {
            ctx.write(msg, promise)
        } else {
            submitOrdered(ctx, msg, promise) { msg }
        }
    }

    override fun flush(ctx: ChannelHandlerContext) {
        if (pendingOrderedWrites == 0) {
            ctx.flush()
            return
        }

        try {
            compressionExecutor.execute {
                try {
                    ctx.executor().execute { ctx.flush() }
                } catch (cause: Throwable) {
                    ctx.fireExceptionCaught(cause)
                }
            }
        } catch (cause: Throwable) {
            ctx.fireExceptionCaught(cause)
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        clearEventLoopState()
        releaseStreamingEncoder()
        super.channelInactive(ctx)
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        clearEventLoopState()
        releaseStreamingEncoder()
        super.handlerRemoved(ctx)
    }

    private fun compressionEncoding(response: HttpResponse): String? {
        if (response.status().codeClass() == HttpStatusClass.INFORMATIONAL) return null

        val request = (if (requests.isEmpty()) null else requests.removeFirst()) ?: return null
        if (!responseCanHaveEncodedBody(response, request) || !isCompressionCandidate(response)) return null

        addAcceptEncodingVary(response)
        return request.acceptEncoding.takeIf { willEncode(response, it) }
    }

    private fun addAcceptEncodingVary(response: HttpResponse) {
        val acceptEncoding = HttpHeaderNames.ACCEPT_ENCODING.toString()
        val alreadyCovered = response.headers().getAll(HttpHeaderNames.VARY).any { value ->
            value.split(',').any { token ->
                val name = token.trim()
                name == "*" || name.equals(acceptEncoding, ignoreCase = true)
            }
        }
        if (!alreadyCovered) {
            response.headers().add(HttpHeaderNames.VARY, HttpHeaderNames.ACCEPT_ENCODING)
        }
    }

    private fun responseCanHaveEncodedBody(response: HttpResponse, request: RequestEncoding): Boolean {
        val status = response.status().code()
        if (status < 200 || status == 204 || status == 304) return false
        if (response.protocolVersion() == HttpVersion.HTTP_1_0) return false
        if (request.method == HttpMethod.HEAD) return false
        if (request.method == HttpMethod.CONNECT && status == 200) return false
        if (response is HttpContent && !response.content().isReadable) return false
        return true
    }

    private fun submitOrdered(
        ctx: ChannelHandlerContext,
        msg: Any,
        promise: ChannelPromise,
        work: () -> Any,
    ) {
        addPendingWrite(ctx, msg)
        try {
            compressionExecutor.execute {
                val output = try {
                    work()
                } catch (cause: Throwable) {
                    ReferenceCountUtil.safeRelease(msg)
                    deliverFailure(ctx, promise, cause)
                    return@execute
                }

                try {
                    ctx.executor().execute {
                        try {
                            ctx.write(output, promise)
                        } finally {
                            removePendingWrite()
                        }
                    }
                } catch (cause: Throwable) {
                    ReferenceCountUtil.safeRelease(output)
                    promise.tryFailure(cause)
                }
            }
        } catch (cause: Throwable) {
            removePendingWrite()
            ReferenceCountUtil.safeRelease(msg)
            promise.tryFailure(cause)
            ctx.fireExceptionCaught(cause)
        }
    }

    private fun deliverFailure(
        ctx: ChannelHandlerContext,
        promise: ChannelPromise,
        cause: Throwable,
    ) {
        try {
            ctx.executor().execute {
                removePendingWrite()
                promise.tryFailure(cause)
                ctx.fireExceptionCaught(cause)
            }
        } catch (_: Throwable) {
            promise.tryFailure(cause)
        }
    }

    private fun addPendingWrite(ctx: ChannelHandlerContext, msg: Any) {
        check(pendingWriteAccountingActive) { "Compression handler is no longer active" }
        val content = when (msg) {
            is ByteBuf -> msg.retainedDuplicate()
            is ByteBufHolder -> msg.content().retainedDuplicate()
            else -> Unpooled.EMPTY_BUFFER
        }
        val marker = DefaultHttp2DataFrame(content, false)
        try {
            // PendingWriteQueue uses the channel pipeline's native pending-byte tracker. In
            // particular, an HTTP/2 stream has no ChannelOutboundBuffer of its own, but its
            // pipeline updates the stream's write-watermark state. Use a DATA-frame marker so the
            // HTTP/2 message-size estimator accounts for the retained payload, not just frame
            // overhead. The marker retains the payload without copying it and is never written.
            pendingWriteAccounting.add(marker, ctx.voidPromise())
        } catch (cause: Throwable) {
            marker.release()
            throw cause
        }
        pendingOrderedWrites++
    }

    private fun removePendingWrite() {
        if (!pendingWriteAccountingActive) return
        pendingOrderedWrites--
        checkNotNull(pendingWriteAccounting.remove()) {
            "Compression pending-write accounting was empty"
        }
    }

    private fun encodeFullResponse(response: FullHttpResponse, acceptEncoding: String): FullHttpResponse {
        val result = checkNotNull(beginEncode(response, acceptEncoding)) {
            "Compression eligibility changed while encoding a full response"
        }
        val encoder = result.contentEncoder()
        val encoded = ArrayList<ByteBuf>()
        try {
            encoder.writeOutbound(response.content().retain())
            drainEncoder(encoder, encoded)
            encoder.finish()
            drainEncoder(encoder, encoded)

            val content = Unpooled.wrappedBuffer(*encoded.toTypedArray())
            val headers = response.headers().copy()
            headers.set(HttpHeaderNames.CONTENT_ENCODING, result.targetContentEncoding())
            if (headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
                headers.setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
            } else {
                headers.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
            }

            val compressed = DefaultFullHttpResponse(
                response.protocolVersion(),
                response.status(),
                content,
                headers,
                response.trailingHeaders().copy(),
            )
            compressed.decoderResult = response.decoderResult()
            response.release()
            return compressed
        } catch (cause: Throwable) {
            encoded.forEach(ReferenceCountUtil::safeRelease)
            encoder.finishAndReleaseAll()
            throw cause
        }
    }

    private fun beginStreamingResponse(response: HttpResponse, acceptEncoding: String): HttpResponse {
        check(streamingEncoder == null) { "Previous compressed response has not completed" }
        val result = checkNotNull(beginEncode(response, acceptEncoding)) {
            "Compression eligibility changed while starting a streaming response"
        }
        streamingEncoder = result.contentEncoder()
        response.headers().set(HttpHeaderNames.CONTENT_ENCODING, result.targetContentEncoding())
        response.headers().remove(HttpHeaderNames.CONTENT_LENGTH)
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
        return response
    }

    private fun encodeStreamingContent(content: HttpContent): HttpContent {
        val encoder = checkNotNull(streamingEncoder) { "Compressed response has no active encoder" }
        val encoded = ArrayList<ByteBuf>()
        try {
            encoder.writeOutbound(content.content().retain())
            drainEncoder(encoder, encoded)
            if (content is LastHttpContent) {
                encoder.finish()
                drainEncoder(encoder, encoded)
                streamingEncoder = null
            }

            val compressed = Unpooled.wrappedBuffer(*encoded.toTypedArray())
            val output = if (content is LastHttpContent) {
                DefaultLastHttpContent(compressed, content.trailingHeaders().copy())
            } else {
                DefaultHttpContent(compressed)
            }
            ReferenceCountUtil.safeRelease(content)
            return output
        } catch (cause: Throwable) {
            encoded.forEach(ReferenceCountUtil::safeRelease)
            encoder.finishAndReleaseAll()
            streamingEncoder = null
            throw cause
        }
    }

    private fun drainEncoder(encoder: EmbeddedChannel, output: MutableList<ByteBuf>) {
        while (true) {
            val buffer = encoder.readOutbound<ByteBuf>() ?: return
            if (buffer.isReadable) {
                output.add(buffer)
            } else {
                buffer.release()
            }
        }
    }

    private fun clearEventLoopState() {
        requests.clear()
        responseCompressionActive = false
        if (pendingWriteAccountingActive) {
            pendingWriteAccountingActive = false
            pendingOrderedWrites = 0
            if (!pendingWriteAccounting.isEmpty) {
                pendingWriteAccounting.removeAndFailAll(
                    IllegalStateException("Compression handler closed with pending writes"),
                )
            }
        }
    }

    private fun releaseStreamingEncoder() {
        val cleanup = Runnable {
            streamingEncoder?.finishAndReleaseAll()
            streamingEncoder = null
        }
        try {
            compressionExecutor.execute(cleanup)
        } catch (_: Throwable) {
            cleanup.run()
        }
    }

    private fun acceptedEncoding(request: HttpRequest): String {
        val values = request.headers().getAll(HttpHeaderNames.ACCEPT_ENCODING)
        return if (values.isEmpty()) HttpHeaderValues.IDENTITY.toString() else values.joinToString(",")
    }
}

/** Installs one compressor and pins its encoder state to one ordered codec executor. */
internal fun ChannelPipeline.addSelectiveCompression(codecGroup: EventExecutorGroup) {
    addLast(
        PipelineHandlerNames.COMPRESSOR,
        CompressionOffloadHandler(codecGroup.next()),
    )
}
