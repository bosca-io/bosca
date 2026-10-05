package bosca.storage.service

import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AbortOnEarlyCloseInputStreamTest {

    private val aborted = AtomicBoolean(false)

    private fun stream(size: Int, declaredLength: Long? = size.toLong()) = AbortOnEarlyCloseInputStream(
        ResponseInputStream(
            GetObjectResponse.builder().contentLength(declaredLength).build(),
            AbortableInputStream.create(ByteArrayInputStream(ByteArray(size))) { aborted.set(true) },
        ),
    )

    @Test
    fun `closing before the object is read aborts the connection instead of draining it`() {
        stream(10).use { input -> input.read(ByteArray(4)) }

        assertTrue(aborted.get())
    }

    @Test
    fun `closing after reading exactly the content length does not abort`() {
        stream(10).use { input ->
            input.read(ByteArray(6))
            input.read()
            input.skip(3)
        }

        assertFalse(aborted.get(), "A fully read object keeps its pooled connection")
    }

    @Test
    fun `closing after end of stream does not abort even without a content length`() {
        stream(3, declaredLength = null).use { input -> input.readAllBytes() }

        assertFalse(aborted.get())
    }
}
