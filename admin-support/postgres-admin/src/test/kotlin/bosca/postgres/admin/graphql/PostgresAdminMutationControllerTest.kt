package bosca.postgres.admin.graphql

import bosca.postgres.admin.service.PostgresAdminService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that [PostgresAdminMutationController] correctly delegates to [PostgresAdminService]
 * after admin authorization, and rejects unauthorized access for all mutation endpoints.
 */
class PostgresAdminMutationControllerTest {

    private val service = mockk<PostgresAdminService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val controller = PostgresAdminMutationController(service, groupEvaluator)
    private val auth = mockk<AuthenticationContext>()

    // region cancelQuery

    @Test
    fun `cancelQuery delegates to service and returns true on success`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.cancelQuery(42) } returns true

        val result = controller.cancelQuery(auth, 42)
        assertTrue(result)
        coVerify { service.cancelQuery(42) }
    }

    @Test
    fun `cancelQuery returns false when backend not found`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.cancelQuery(9999) } returns false

        val result = controller.cancelQuery(auth, 9999)
        assertFalse(result)
    }

    @Test
    fun `cancelQuery rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.cancelQuery(auth, 42) }
    }

    // endregion

    // region terminateBackend

    @Test
    fun `terminateBackend delegates to service and returns true on success`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.terminateBackend(42) } returns true

        val result = controller.terminateBackend(auth, 42)
        assertTrue(result)
        coVerify { service.terminateBackend(42) }
    }

    @Test
    fun `terminateBackend returns false when backend not found`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.terminateBackend(9999) } returns false

        val result = controller.terminateBackend(auth, 9999)
        assertFalse(result)
    }

    @Test
    fun `terminateBackend rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.terminateBackend(auth, 42) }
    }

    // endregion

    // region analyzeTable

    @Test
    fun `analyzeTable delegates with schema and table name`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.analyzeTable("public", "users") } returns true

        val result = controller.analyzeTable(auth, "public", "users")
        assertTrue(result)
        coVerify { service.analyzeTable("public", "users") }
    }

    @Test
    fun `analyzeTable returns false on failure`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.analyzeTable("public", "nonexistent") } returns false

        val result = controller.analyzeTable(auth, "public", "nonexistent")
        assertFalse(result)
    }

    @Test
    fun `analyzeTable rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.analyzeTable(auth, "public", "users") }
    }

    // endregion

    // region resetStatStatements

    @Test
    fun `resetStatStatements delegates to service`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.resetStatStatements() } returns true

        val result = controller.resetStatStatements(auth)
        assertTrue(result)
    }

    @Test
    fun `resetStatStatements returns false when extension missing`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.resetStatStatements() } returns false

        val result = controller.resetStatStatements(auth)
        assertFalse(result)
    }

    @Test
    fun `resetStatStatements rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.resetStatStatements(auth) }
    }

    // endregion

    // region vacuumTable

    @Test
    fun `vacuumTable delegates with default full=false when null`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.vacuumTable("public", "users", false) } returns true

        val result = controller.vacuumTable(auth, "public", "users", null)
        assertTrue(result)
        coVerify { service.vacuumTable("public", "users", false) }
    }

    @Test
    fun `vacuumTable delegates with full=true when specified`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.vacuumTable("public", "users", true) } returns true

        val result = controller.vacuumTable(auth, "public", "users", true)
        assertTrue(result)
        coVerify { service.vacuumTable("public", "users", true) }
    }

    @Test
    fun `vacuumTable returns false on failure`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.vacuumTable("public", "missing_table", false) } returns false

        val result = controller.vacuumTable(auth, "public", "missing_table", null)
        assertFalse(result)
    }

    @Test
    fun `vacuumTable rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.vacuumTable(auth, "public", "users", null) }
    }

    // endregion

    // region reindex

    @Test
    fun `reindex delegates with schema and index name`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.reindex("public", "users_email_idx") } returns true

        val result = controller.reindex(auth, "public", "users_email_idx")
        assertTrue(result)
        coVerify { service.reindex("public", "users_email_idx") }
    }

    @Test
    fun `reindex returns false on failure`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.reindex("public", "nonexistent_idx") } returns false

        val result = controller.reindex(auth, "public", "nonexistent_idx")
        assertFalse(result)
    }

    @Test
    fun `reindex rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.reindex(auth, "public", "users_email_idx") }
    }

    // endregion
}
