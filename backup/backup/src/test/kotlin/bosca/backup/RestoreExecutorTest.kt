package bosca.backup

import bosca.di.annotation.InternalDI
import io.mockk.coEvery
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun `wrong archive format fails before destination changes`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            fixture.restore(manifest = manifest().copy(format = "another-format"))
        }
        verifyUntouched()
    }

    @Test
    fun `missing declared database payload fails before destination changes`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            fixture.restore(manifest = manifest("traits" to 1L))
        }
        verifyUntouched()
    }

    @Test
    fun `missing empty declared database payload is still an incomplete archive`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            fixture.restore(manifest = manifest("traits" to 0L))
        }
        verifyUntouched()
    }

    @Test
    fun `negative table count fails before destination changes`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            fixture.restore(data = mapOf("traits" to ""), manifest = manifest("traits" to -1L))
        }
        verifyUntouched()
    }

    @Test
    fun `unsupported declared table fails before destination changes`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            fixture.restore(data = mapOf("unknown_table" to ""))
        }
        verifyUntouched()
    }

    @Test
    fun `undeclared database payload fails before destination changes`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            fixture.restore(data = mapOf("traits" to ""), manifest = manifest())
        }
        verifyUntouched()
    }

    @Test
    fun `database row count mismatch fails before destination changes`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            fixture.restore(data = mapOf("traits" to ""), manifest = manifest("traits" to 1L))
        }
        verifyUntouched()
    }

    @Test
    fun `malformed row fails during preflight before destination changes`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            fixture.restore(data = mapOf("traits" to "invalid-json"))
        }
        verifyUntouched()
    }

    @Test
    fun `invalid file path fails before destination changes`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            fixture.restore(files = mapOf("metadata/../content" to "data".toByteArray()))
        }
        verifyUntouched()
    }

    @Test
    fun `skip conflicts does not swallow non-conflict database errors`() = runTest {
        verifyDatabaseFailure(ConflictStrategy.SKIP)
    }

    @Test
    fun `overwrite conflicts does not swallow non-conflict database errors`() = runTest {
        verifyDatabaseFailure(ConflictStrategy.OVERWRITE)
    }

    @Test
    fun `row insert cancellation propagates under skip conflicts`() = runTest {
        val cancelled = CancellationException("Cancelled database restore")
        every { fixture.statement.executeUpdate() } throws cancelled

        fixture.assertPropagated(cancelled, assertFailsWith<CancellationException> {
            fixture.restore(data = mapOf("traits" to "{\"id\":\"test\"}"))
        })
        verify(exactly = 0) { fixture.connection.commit() }
        verify { fixture.connection.rollback() }
    }

    @Test
    fun `blank lines do not contribute to source row counts`() = runTest {
        fixture.restore(data = mapOf("traits" to "\n{\"id\":\"test\"}\n\n"))

        verify(exactly = 1) { fixture.statement.executeUpdate() }
        verify(exactly = 1) { fixture.connection.commit() }
    }

    private fun manifest(vararg counts: Pair<String, Long>) = BackupManifest(
        createdAt = "2026-10-15T00:00:00Z", counts = counts.toMap().toMutableMap(),
    )

    private fun verifyUntouched() {
        verify(exactly = 0) { fixture.connection.prepareStatement(any()) }
        assertTrue(fixture.writtenFiles.isEmpty())
    }

    private suspend fun verifyDatabaseFailure(strategy: ConflictStrategy) {
        val failure = SQLException("Required field is null", "23502")
        every { fixture.statement.executeUpdate() } throws failure

        fixture.assertPropagated(failure, assertFailsWith<SQLException> {
            fixture.restore(
                files = mapOf("metadata/item/1/content" to "data".toByteArray()),
                data = mapOf("traits" to "{\"id\":\"test\"}"),
                conflictStrategy = strategy,
            )
        })
        verify(exactly = 0) { fixture.connection.commit() }
        verify { fixture.connection.rollback() }
        assertTrue(fixture.writtenFiles.isEmpty())
    }
}
