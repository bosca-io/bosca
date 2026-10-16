package bosca.backup

import bosca.di.annotation.InternalDI
import io.mockk.coEvery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

@OptIn(InternalDI::class)
class RestoreExecutorTest {
    private lateinit var fixture: BackupExecutorFixture

    @BeforeTest
    fun setUp() { fixture = BackupExecutorFixture() }

    @AfterTest
    fun tearDown() = runBlocking { fixture.close() }

    @Test
    fun `required file write failure fails the restore`() = runTest {
        val failure = IOException("Destination unavailable")
        coEvery { fixture.storage.setInputStream(any(), any(), any()) } throws failure

        fixture.assertPropagated(failure, assertFailsWith<IOException> {
            fixture.restore(mapOf("metadata/item/1/content" to "data".toByteArray()))
        })
    }

    @Test
    fun `cancellation during file restore propagates`() = runTest {
        val cancelled = CancellationException("Cancelled restore")
        coEvery { fixture.storage.setInputStream(any(), any(), any()) } throws cancelled

        fixture.assertPropagated(cancelled, assertFailsWith<CancellationException> {
            fixture.restore(mapOf("metadata/item/1/content" to "data".toByteArray()))
        })
    }

    @Test
    fun `successful restore preserves normal and empty file bytes`() = runTest {
        val files = mapOf("metadata/item/1/content" to "data".toByteArray(), "collection/item/content" to ByteArray(0))

        fixture.restore(files)

        for ((path, bytes) in files) assertContentEquals(bytes, fixture.writtenFiles[path])
    }
}
