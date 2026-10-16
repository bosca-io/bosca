package bosca.git.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class LfsUploadCleanupTest {
    private val input = mockk<InputStream>(relaxed = true)
    private val output = mockk<OutputStream>(relaxed = true)

    @Test
    fun `both pipe ends close without a failure`() {
        closeLfsUploadPipe(input, output)

        verify(exactly = 1) { input.close() }
        verify(exactly = 1) { output.close() }
    }

    @Test
    fun `close failures are suppressed onto the upload failure including cancellation`() {
        for (failure in listOf(IOException("upload failed"), CancellationException("request cancelled"))) {
            val inputFailure = IOException("input close failed")
            val outputFailure = IOException("output close failed")
            every { input.close() } throws inputFailure
            every { output.close() } throws outputFailure

            closeLfsUploadPipe(input, output, failure)

            assertContentEquals(arrayOf(inputFailure, outputFailure), failure.suppressed)
        }
        verify(exactly = 2) { input.close() }
        verify(exactly = 2) { output.close() }
    }

    @Test
    fun `first close failure propagates when there is no upload failure`() {
        val inputFailure = IOException("input close failed")
        val outputFailure = IOException("output close failed")
        every { input.close() } throws inputFailure
        every { output.close() } throws outputFailure

        val actual = assertFailsWith<IOException> { closeLfsUploadPipe(input, output) }

        assertSame(inputFailure, actual)
        assertContentEquals(arrayOf(outputFailure), actual.suppressed)
        verify(exactly = 1) { output.close() }
    }

    @Test
    fun `output close failure propagates after a successful input close`() {
        val failure = IOException("output close failed")
        every { output.close() } throws failure

        assertSame(failure, assertFailsWith<IOException> { closeLfsUploadPipe(input, output) })
        verify(exactly = 1) { input.close() }
    }

    @Test
    fun `repeated failure instance does not prevent closing the other pipe end`() {
        val failure = IOException("upload failed")
        every { input.close() } throws failure

        closeLfsUploadPipe(input, output, failure)

        verify(exactly = 1) { output.close() }
        assertContentEquals(emptyArray(), failure.suppressed)
    }
}
