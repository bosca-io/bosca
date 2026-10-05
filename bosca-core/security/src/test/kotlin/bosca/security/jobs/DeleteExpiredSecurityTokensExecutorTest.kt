package bosca.security.jobs

import bosca.security.service.SecurityService
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DeleteExpiredSecurityTokensExecutorTest {
    @Test
    fun `execute deletes expired authentication records`() = runTest {
        val securityService = mockk<SecurityService>(relaxed = true)

        DeleteExpiredSecurityTokensExecutor(securityService).execute()

        coVerify(exactly = 1) { securityService.deleteExpiredRefreshToken() }
    }

    @Test
    fun `job payload serializes as an empty maintenance request`() {
        val encoded = Json.encodeToString(DeleteExpiredSecurityTokensJob.serializer(), DeleteExpiredSecurityTokensJob())

        assertEquals("{}", encoded)
        Json.decodeFromString(DeleteExpiredSecurityTokensJob.serializer(), encoded)
    }
}
