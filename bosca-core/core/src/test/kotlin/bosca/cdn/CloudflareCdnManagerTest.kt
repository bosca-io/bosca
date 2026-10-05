package bosca.cdn

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudflareCdnManagerTest {

    @Test
    fun `clearCache returns true on success`() = runBlocking {
        val mockCallFactory = mockk<Call.Factory>()
        val mockCall = mockk<Call>()
        val successResponse = Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("""{"success": true}""".toResponseBody("application/json".toMediaType()))
            .build()

        every { mockCallFactory.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns successResponse

        val manager = CloudflareCdnManager("token", "zone", mockCallFactory)
        assertTrue(manager.clearCache())
        
        verify { mockCallFactory.newCall(any()) }
        verify { mockCall.execute() }
    }

    @Test
    fun `clearCache returns false on failure`() = runBlocking {
        val mockCallFactory = mockk<Call.Factory>()
        val mockCall = mockk<Call>()
        val failureResponse = Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .body("""{"success": false}""".toResponseBody("application/json".toMediaType()))
            .build()

        every { mockCallFactory.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns failureResponse

        val manager = CloudflareCdnManager("token", "zone", mockCallFactory)
        assertFalse(manager.clearCache())
    }
    
    @Test
    fun `clearCache returns false on success=false in body`() = runBlocking {
        val mockCallFactory = mockk<Call.Factory>()
        val mockCall = mockk<Call>()
        val successResponse = Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("""{"success": false, "errors": []}""".toResponseBody("application/json".toMediaType()))
            .build()

        every { mockCallFactory.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns successResponse

        val manager = CloudflareCdnManager("token", "zone", mockCallFactory)
        assertFalse(manager.clearCache())
    }
}
