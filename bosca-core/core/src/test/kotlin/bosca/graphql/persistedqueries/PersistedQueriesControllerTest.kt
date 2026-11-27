package bosca.graphql.persistedqueries

import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersistedQueriesControllerTest {

    private val repository = mockk<PersistedQueryRepository>()
    private val authenticationContext = mockk<AuthenticationContext>()
    private val principal = mockk<AuthenticatedPrincipal>()
    private val controller = PersistedQueriesController(repository)

    @Test
    fun `query returns first matching persisted query for an administrator`() = runTest {
        val persistedQuery = PersistedQuery(
            application = "studio",
            query = "query { server { features } }",
            sha256 = "sha256"
        )
        every { authenticationContext.principal() } returns principal
        every { principal.hasGroup("sa") } returns false
        every { principal.hasGroup("administrators") } returns true
        coEvery { repository.findBySha256("sha256") } returns listOf(persistedQuery)

        assertEquals(persistedQuery, controller.query(authenticationContext, "sha256"))
    }

    @Test
    fun `query hides persisted queries from unauthorized principals`() = runTest {
        every { authenticationContext.principal() } returns null

        assertNull(controller.query(authenticationContext, "sha256"))
        coVerify(exactly = 0) { repository.findBySha256(any()) }
    }

    @Test
    fun `service administrators can resolve missing queries without checking the administrator group`() = runTest {
        every { authenticationContext.principal() } returns principal
        every { principal.hasGroup("sa") } returns true
        coEvery { repository.findBySha256("missing") } returns emptyList()

        assertNull(controller.query(authenticationContext, "missing"))

        coVerify(exactly = 1) { repository.findBySha256("missing") }
        io.mockk.verify(exactly = 0) { principal.hasGroup("administrators") }
    }

    @Test
    fun `authenticated users without an administrative group cannot query the repository`() = runTest {
        every { authenticationContext.principal() } returns principal
        every { principal.hasGroup("sa") } returns false
        every { principal.hasGroup("administrators") } returns false

        assertNull(controller.query(authenticationContext, "sha256"))

        coVerify(exactly = 0) { repository.findBySha256(any()) }
    }
}
