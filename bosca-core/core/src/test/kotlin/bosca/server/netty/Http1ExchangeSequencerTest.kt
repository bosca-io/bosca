package bosca.server.netty

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.ReferenceCountUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Http1ExchangeSequencerTest {

    @Test
    fun `later request is held until the preceding final response is written`() {
        val requests = mutableListOf<String>()
        val codec = HttpServerCodec()
        val channel = EmbeddedChannel(
            codec,
            Http1ExchangeSequencer(codec),
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    if (msg is DefaultFullHttpRequest) requests += msg.uri()
                    ReferenceCountUtil.release(msg)
                }
            },
        )

        try {
            channel.writeInbound(request("/first"))
            channel.writeInbound(request("/second"))
            channel.writeInbound(request("/third"))
            assertEquals(listOf("/first"), requests)

            assertTrue(channel.writeOutbound(response("first")))
            channel.runPendingTasks()
            assertEquals(listOf("/first", "/second"), requests)

            assertTrue(channel.writeOutbound(response("second")))
            channel.runPendingTasks()
            assertEquals(listOf("/first", "/second", "/third"), requests)

            assertTrue(channel.writeOutbound(response("third")))
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `informational response does not release the next request`() {
        val requests = mutableListOf<String>()
        val codec = HttpServerCodec()
        val channel = EmbeddedChannel(
            codec,
            Http1ExchangeSequencer(codec),
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    if (msg is DefaultFullHttpRequest) requests += msg.uri()
                    ReferenceCountUtil.release(msg)
                }
            },
        )

        try {
            channel.writeInbound(request("/first"))
            channel.writeInbound(request("/second"))

            assertTrue(channel.writeOutbound(DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE)))
            channel.runPendingTasks()
            assertEquals(listOf("/first"), requests)

            assertTrue(channel.writeOutbound(response("first")))
            channel.runPendingTasks()
            assertEquals(listOf("/first", "/second"), requests)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `connection close response discards pipelined requests instead of dispatching them`() {
        val requests = mutableListOf<String>()
        val codec = HttpServerCodec()
        val channel = EmbeddedChannel(
            codec,
            Http1ExchangeSequencer(codec),
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    if (msg is DefaultFullHttpRequest) requests += msg.uri()
                    ReferenceCountUtil.release(msg)
                }
            },
        )
        val queued = request("/must-not-run")

        try {
            channel.writeInbound(request("/closing"))
            channel.writeInbound(queued)
            assertEquals(listOf("/closing"), requests)

            val response = response("closing")
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
            assertTrue(channel.writeOutbound(response))
            channel.runPendingTasks()

            assertEquals(listOf("/closing"), requests)
            assertEquals(0, queued.refCnt(), "Queued work must be released when the connection cannot be reused")

            val late = request("/also-must-not-run")
            assertFalse(channel.writeInbound(late))
            assertEquals(0, late.refCnt())
            assertEquals(listOf("/closing"), requests)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `closing a connection releases queued request content`() {
        val codec = HttpServerCodec()
        val channel = EmbeddedChannel(
            codec,
            Http1ExchangeSequencer(codec),
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    ReferenceCountUtil.release(msg)
                }
            },
        )
        val queued = ArrayList<io.netty.util.ReferenceCounted>()

        channel.writeInbound(request("/active"))
        request("/queued").also {
            queued += it
            assertFalse(channel.writeInbound(it))
        }
        repeat(63) {
            DefaultHttpContent(Unpooled.buffer(0)).also { content ->
                queued += content
                assertFalse(channel.writeInbound(content))
            }
        }
        assertFalse(channel.config().isAutoRead)
        queued.forEach { assertEquals(1, it.refCnt()) }

        channel.finishAndReleaseAll()
        queued.forEach { assertEquals(0, it.refCnt()) }
    }

    @Test
    fun `streaming queued request resumes reads after its backlog drains`() {
        val received = mutableListOf<Any>()
        val codec = HttpServerCodec()
        val channel = EmbeddedChannel(
            codec,
            Http1ExchangeSequencer(codec),
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    received += msg
                    ReferenceCountUtil.release(msg)
                }
            },
        )

        try {
            channel.writeInbound(request("/active"))
            channel.writeInbound(DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/streaming"))
            repeat(63) {
                channel.writeInbound(DefaultHttpContent(Unpooled.buffer(0)))
            }
            channel.writeInbound(DefaultLastHttpContent(Unpooled.buffer(0)))
            assertFalse(channel.config().isAutoRead)

            assertTrue(channel.writeOutbound(response("active")))
            channel.runPendingTasks()

            assertTrue(channel.config().isAutoRead)
            assertEquals(66, received.size)
            assertTrue(channel.writeOutbound(response("streaming")))
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `removing sequencer releases queued reads and restores auto read`() {
        val codec = HttpServerCodec()
        val sequencer = Http1ExchangeSequencer(codec)
        val channel = EmbeddedChannel(
            codec,
            sequencer,
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    ReferenceCountUtil.release(msg)
                }
            },
        )
        val queued = ArrayList<io.netty.util.ReferenceCounted>()

        try {
            channel.writeInbound(request("/active"))
            request("/queued").also {
                queued += it
                channel.writeInbound(it)
            }
            repeat(63) {
                DefaultHttpContent(Unpooled.buffer(0)).also { content ->
                    queued += content
                    channel.writeInbound(content)
                }
            }
            assertFalse(channel.config().isAutoRead)

            channel.pipeline().remove(sequencer)

            assertTrue(channel.config().isAutoRead)
            queued.forEach { assertEquals(0, it.refCnt()) }
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `missing codec releases requests waiting to be replayed`() {
        val codec = HttpServerCodec()
        val sequencer = Http1ExchangeSequencer(codec)
        val channel = EmbeddedChannel(
            codec,
            sequencer,
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    ReferenceCountUtil.release(msg)
                }
            },
        )
        val second = request("/second")
        val third = request("/third")

        try {
            channel.writeInbound(request("/first"))
            channel.writeInbound(second)
            channel.writeInbound(third)
            channel.pipeline().remove(codec)

            assertTrue(channel.writeOutbound(response("first")))
            channel.runPendingTasks()

            assertEquals(0, second.refCnt())
            assertEquals(0, third.refCnt())
            ReferenceCountUtil.safeRelease(channel.readOutbound<Any>())
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `switching protocols releases queued HTTP requests and disables sequencing`() {
        val requests = mutableListOf<String>()
        val codec = HttpServerCodec()
        val channel = EmbeddedChannel(
            codec,
            Http1ExchangeSequencer(codec),
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    if (msg is DefaultFullHttpRequest) requests += msg.uri()
                    ReferenceCountUtil.release(msg)
                }
            },
        )
        val queued = request("/queued")

        try {
            channel.writeInbound(request("/upgrade"))
            channel.writeInbound(queued)
            assertEquals(1, queued.refCnt())

            assertTrue(
                channel.writeOutbound(
                    DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS),
                ),
            )
            assertEquals(0, queued.refCnt())

            channel.writeInbound(request("/after-upgrade"))
            assertEquals(listOf("/upgrade", "/after-upgrade"), requests)
            assertTrue(channel.writeOutbound(response("unsequenced")))
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `outbound content without an active exchange passes through`() {
        val codec = HttpServerCodec()
        val channel = EmbeddedChannel(Http1ExchangeSequencer(codec))
        val content = Unpooled.copiedBuffer("raw", Charsets.UTF_8)

        try {
            assertTrue(channel.writeOutbound(content))
            val written = channel.readOutbound<io.netty.buffer.ByteBuf>()
            assertEquals("raw", written.toString(Charsets.UTF_8))
            written.release()
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `manual read mode is not changed when backlog reaches pause threshold`() {
        val codec = HttpServerCodec()
        val sequencer = Http1ExchangeSequencer(codec)
        val channel = EmbeddedChannel(
            codec,
            sequencer,
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    ReferenceCountUtil.release(msg)
                }
            },
        )

        try {
            channel.config().isAutoRead = false
            channel.writeInbound(request("/active"))
            channel.writeInbound(request("/queued"))
            repeat(63) {
                channel.writeInbound(DefaultHttpContent(Unpooled.buffer(0)))
            }

            assertFalse(channel.config().isAutoRead)
            channel.pipeline().remove(sequencer)
            assertFalse(channel.config().isAutoRead)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    private fun request(path: String) =
        DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path)

    private fun response(body: String) = DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1,
        HttpResponseStatus.OK,
        Unpooled.copiedBuffer(body, Charsets.UTF_8),
    )
}
