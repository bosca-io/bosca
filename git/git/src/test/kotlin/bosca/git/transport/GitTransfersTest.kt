package bosca.git.transport

import bosca.server.ContentType
import bosca.server.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [streamGitExchange] hands JGit the request body and the response, and ends when JGit does. */
class GitTransfersTest {

    private val contentType = ContentType("application", "x-git-test-result")

    @Test
    fun `JGit reads the request body and writes the response directly`() = runTest {
        val (call, response) = recordedCall(body = "want abc".toByteArray())

        streamGitExchange(call, contentType) { input, output ->
            output.write("ack:".toByteArray() + input.readAllBytes())
        }

        assertEquals("ack:want abc", response.bodyText)
        assertEquals(HttpStatusCode.OK, response.status)
        // The transfer's own withTimeout bounds it (so expiry is reported); the response sets none.
        coVerify { call.respondStreaming(contentType, HttpStatusCode.OK, isNull(), any()) }
    }

    @Test
    fun `JGit finishing before the body ends closes the body and completes the exchange`() = runTest {
        var closed = false
        val (call, response) = recordedCall()
        coEvery { call.request.bodyInputStream() } returns object : InputStream() {
            override fun read(): Int = 0
            override fun close() {
                closed = true
            }
        }

        streamGitExchange(call, contentType) { _, output ->
            // Answer without reading the request, as a failing upload-pack does.
            output.write("done".toByteArray())
        }

        assertEquals("done", response.bodyText)
        assertTrue(closed, "The unread rest of the body must be discarded")
    }

    @Test
    fun `a request body that fails while JGit is reading fails the exchange`() = runTest {
        val (call, _) = recordedCall()
        coEvery { call.request.bodyInputStream() } returns object : InputStream() {
            override fun read(): Int = throw IOException("client reset")
        }

        val failure = assertFailsWith<IOException> {
            streamGitExchange(call, contentType) { input, _ -> input.readAllBytes() }
        }
        assertEquals("client reset", failure.message)
    }

    @Test
    fun `a transfer past its time limit fails with a timeout instead of ending quietly`() = runTest {
        val (call, _) = recordedCall()
        // A body that never arrives: the read waits until the time limit cancels it.
        coEvery { call.request.bodyInputStream() } coAnswers {
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            object : InputStream() {
                override fun read(): Int = kotlinx.coroutines.runBlocking(checkNotNull(job)) {
                    kotlinx.coroutines.awaitCancellation()
                }
            }
        }

        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            streamGitExchange(call, contentType, timeLimit = 50.milliseconds) { input, _ -> input.read() }
        }
    }
}
