package bosca.server.http

import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SuspendCallTest {

    private fun buildResponse(code: Int = 200, body: String = "ok"): Response {
        return Response.Builder()
            .request(Request.Builder().url("http://localhost/test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody("text/plain".toMediaType()))
            .build()
    }

    @Test
    fun `await returns response on success`() = runTest {
        val call = mockk<Call>(relaxed = true)
        val callbackSlot = slot<Callback>()
        every { call.enqueue(capture(callbackSlot)) } answers {
            callbackSlot.captured.onResponse(call, buildResponse(200, "success"))
        }

        val response = call.await()
        assertEquals(200, response.code)
    }

    @Test
    fun `await throws IOException on failure`() = runTest {
        val call = mockk<Call>(relaxed = true)
        val callbackSlot = slot<Callback>()
        every { call.enqueue(capture(callbackSlot)) } answers {
            callbackSlot.captured.onFailure(call, IOException("connection refused"))
        }

        assertFailsWith<IOException> {
            call.await()
        }
    }

    @Test
    fun `await cancels call on coroutine cancellation`() {
        val call = mockk<Call>(relaxed = true)
        val callCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val enqueued = java.util.concurrent.CountDownLatch(1)
        every { call.enqueue(any()) } answers { enqueued.countDown() }
        every { call.cancel() } answers { callCancelled.set(true) }

        @Suppress("OPT_IN_USAGE")
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            call.await()
        }

        assertTrue(enqueued.await(5, java.util.concurrent.TimeUnit.SECONDS), "enqueue should be called")
        // Give invokeOnCancellation registration time to complete
        Thread.sleep(50)
        kotlinx.coroutines.runBlocking {
            job.cancelAndJoin()
        }
        // Give invokeOnCancellation time to execute
        Thread.sleep(100)
        assertTrue(callCancelled.get(), "Call.cancel() should be invoked on coroutine cancellation")
    }

    @Test
    fun `await handles non-200 response codes`() = runTest {
        val call = mockk<Call>(relaxed = true)
        val callbackSlot = slot<Callback>()
        every { call.enqueue(capture(callbackSlot)) } answers {
            callbackSlot.captured.onResponse(call, buildResponse(404, "not found"))
        }

        val response = call.await()
        assertEquals(404, response.code)
    }
}
