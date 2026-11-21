package bosca.server

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerRequestStreamingTest {

    @Test
    fun `streaming with an explicit dispatcher writes there and consumes the body exactly once`() = runBlocking<Unit> {
        val body = RequestBody()
        val first = DefaultHttpContent(Unpooled.wrappedBuffer(byteArrayOf(1, 2)))
        val last = DefaultLastHttpContent(Unpooled.wrappedBuffer(byteArrayOf(3)))
        body.addContent(first)
        body.addContent(last)
        first.release()
        last.release()
        val request = request(body)
        val bytes = ByteArrayOutputStream()
        val writingThreads = mutableListOf<Thread>()
        val writer = AtomicReference<Thread>()
        val output = object : OutputStream() {
            override fun write(value: Int) {
                writingThreads += Thread.currentThread()
                bytes.write(value)
            }
        }
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "request-stream-writer").also(writer::set)
        }
            .asCoroutineDispatcher()
        try {
            assertEquals(3L, request.bodyStreamTo(output, dispatcher))
            assertContentEquals(byteArrayOf(1, 2, 3), bytes.toByteArray())
            assertEquals(3, writingThreads.size)
            assertTrue(writingThreads.all { it === writer.get() })
            assertEquals(0, first.refCnt())
            assertEquals(0, last.refCnt())
            assertFailsWith<IllegalStateException> { request.bodyBytes() }
        } finally {
            dispatcher.close()
            body.discard()
        }
    }

    @Test
    fun `streaming a bodyless request with an explicit dispatcher produces no writes`() = runBlocking<Unit> {
        val request = request(null)
        val output = ByteArrayOutputStream()

        assertEquals(0L, request.bodyStreamTo(output, kotlinx.coroutines.Dispatchers.Unconfined))
        assertEquals(0, output.size())
        assertFailsWith<IllegalStateException> { request.bodyInputStream() }
    }

    @Test
    fun `blocking body streams preserve binary values and consume bodyless requests too`() = runBlocking<Unit> {
        val body = RequestBody()
        val content = DefaultLastHttpContent(Unpooled.wrappedBuffer(byteArrayOf(0, 0xff.toByte())))
        body.addContent(content)
        content.release()
        val withBody = request(body)
        try {
            withBody.bodyInputStream().use { input ->
                assertEquals(0, input.read())
                assertEquals(255, input.read())
                assertEquals(-1, input.read())
            }
            assertEquals(0, content.refCnt())
            assertFailsWith<IllegalStateException> { withBody.bodyBytes() }

            val bodyless = request(null)
            bodyless.bodyInputStream().use { assertEquals(-1, it.read()) }
            assertFailsWith<IllegalStateException> { bodyless.bodyBytes() }
        } finally {
            body.discard()
        }
    }

    private fun request(body: RequestBody?) = ServerRequest(
        DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"), body,
    )
}
