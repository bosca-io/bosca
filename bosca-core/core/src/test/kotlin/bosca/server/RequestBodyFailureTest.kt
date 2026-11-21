package bosca.server

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultLastHttpContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RequestBodyFailureTest {

    @Test
    fun `a size limit crossed after a chunk was handed to the reader still fails and releases both chunks`() = runTest {
        val body = RequestBody(null, 3)
        val reader = async(start = CoroutineStart.UNDISPATCHED) {
            assertFailsWith<RequestBodyTooLargeException> { body.readAllBytes() }
        }
        val first = DefaultHttpContent(Unpooled.wrappedBuffer(byteArrayOf(1, 2)))
        val last = DefaultLastHttpContent(Unpooled.wrappedBuffer(byteArrayOf(3, 4)))
        try {
            body.addContent(first)
            first.release()
            body.addContent(last)
            last.release()

            assertTrue(reader.await().message.orEmpty().contains("3 bytes"))
            assertEquals(0, first.refCnt())
            assertEquals(0, last.refCnt())
        } finally {
            body.discard()
        }
    }

    @Test
    fun `cancellation as a waiting reader receives a chunk releases undelivered data and end markers`() = runTest {
        for (bytes in listOf(byteArrayOf(1, 2, 3), byteArrayOf())) {
            val body = RequestBody()
            val reader = launch(start = CoroutineStart.UNDISPATCHED) { body.readAllBytes() }
            val content = DefaultLastHttpContent(Unpooled.wrappedBuffer(bytes))
            val buffer = content.content()
            try {
                body.addContent(content)
                content.release()
                // The channel has handed the result to the suspended reader, but its continuation
                // has not run. Cancellation must release that handed-off pooled buffer too.
                reader.cancelAndJoin()
                assertTrue(reader.isCancelled)
                if (bytes.isNotEmpty()) assertEquals(0, buffer.refCnt())
            } finally {
                body.discard()
            }
        }
    }

    @Test
    fun `array reads reject an oversized body before allocating and release retained buffers`() = runTest {
        for (limit in listOf(0L, Long.MAX_VALUE)) {
            val buffer = mockk<ByteBuf>()
            every { buffer.readableBytes() } returns Int.MAX_VALUE - 4
            every { buffer.retain() } returns buffer
            every { buffer.release() } returns false
            val body = RequestBody(null, limit)
            body.addContent(DefaultLastHttpContent(buffer))

            val error = assertFailsWith<RequestBodyTooLargeException> { body.readAllBytes() }

            assertEquals("Request body exceeds maximum size of ${Int.MAX_VALUE - 8} bytes", error.message)
            verify(exactly = 1) { buffer.retain() }
            verify(exactly = 1) { buffer.release() }
            verify(exactly = 0) { buffer.readBytes(any<ByteArray>(), any(), any()) }
        }
    }

    @Test
    fun `a streaming destination failure releases the current chunk and preserves the original error`() = runTest {
        val body = RequestBody()
        val current = Unpooled.wrappedBuffer(byteArrayOf(1, 2, 3))
        val queued = Unpooled.wrappedBuffer(byteArrayOf(4))
        body.addContent(DefaultHttpContent(current))
        body.addContent(DefaultLastHttpContent(queued))
        current.release()
        queued.release()
        val failure = IOException("destination disconnected")
        val output = object : OutputStream() {
            override fun write(value: Int) { throw failure }
        }
        try {
            assertSame(failure, assertFailsWith<IOException> { body.streamTo(output, Dispatchers.Unconfined) })
            assertEquals(0, current.refCnt())
            assertEquals(1, queued.refCnt(), "The caller still owns disposal of the remaining request")
        } finally {
            body.discard()
        }
        assertEquals(0, queued.refCnt())
    }

    @Test
    fun `a multipart consumer failure releases its chunk before the caller discards the remainder`() = runTest {
        val body = RequestBody()
        val buffer = Unpooled.wrappedBuffer(byteArrayOf(1))
        body.addContent(DefaultLastHttpContent(buffer))
        buffer.release()
        val failure = IOException("invalid multipart field")

        try {
            assertSame(failure, assertFailsWith<IOException> { body.consumeChunks { throw failure } })
            assertEquals(0, buffer.refCnt())
        } finally {
            body.discard()
        }
    }

    @Test
    fun `blocking reads validate bounds and zero length without waiting for body data`() {
        val body = RequestBody()
        val job = Job()
        try {
            body.inputStream(job).use { input ->
                val bytes = ByteArray(3)
                assertEquals(0, input.read(bytes, bytes.size, 0))
                assertFailsWith<IndexOutOfBoundsException> { input.read(bytes, -1, 1) }
                assertFailsWith<IndexOutOfBoundsException> { input.read(bytes, 2, 2) }
                assertTrue(job.isActive)
            }
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `blocking reader reports end repeatedly and closing it after end is idempotent`() {
        val body = RequestBody()
        val chunk = DefaultLastHttpContent(Unpooled.wrappedBuffer(byteArrayOf(0xff.toByte())))
        body.addContent(chunk)
        chunk.release()
        val job = Job()
        try {
            val input = body.inputStream(job)
            assertEquals(255, input.read())
            assertEquals(-1, input.read())
            assertEquals(-1, input.read(ByteArray(1)))
            assertEquals(0, input.read(ByteArray(0)))
            input.close()
            input.close()
            assertEquals(0, chunk.refCnt())
            assertTrue(job.isActive)
        } finally {
            job.cancel()
        }
    }
}
