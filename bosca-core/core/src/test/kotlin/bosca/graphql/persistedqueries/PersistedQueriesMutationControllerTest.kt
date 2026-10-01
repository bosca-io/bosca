package bosca.graphql.persistedqueries

import bosca.di.ObjectProvider
import bosca.graphql.GraphQLService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersistedQueriesMutationControllerTest {

    private val repository = mockk<PersistedQueryRepository>()
    private val service = mockk<GraphQLService>(relaxed = true)
    private val provider = mockk<ObjectProvider<GraphQLService>>()
    private val authentication = mockk<AuthenticationContext>()
    private val principal = mockk<AuthenticatedPrincipal>()
    private val controller = PersistedQueriesMutationController(repository, provider)

    init {
        coEvery { provider.get() } returns service
    }

    private fun authorize(sa: Boolean, administrator: Boolean) {
        every { authentication.principal() } returns principal
        every { principal.hasGroup("sa") } returns sa
        every { principal.hasGroup("administrators") } returns administrator
    }

    @Test
    fun `mutations reject missing and unauthorized principals`() = runTest {
        every { authentication.principal() } returns null
        assertFailsWith<SecurityException> { controller.add(authentication, "app", "query", "sha") }

        authorize(sa = false, administrator = false)
        assertFailsWith<SecurityException> { controller.delete(authentication, "app", "sha") }
        coVerify(exactly = 0) { repository.upsert(any(), any(), any()) }
        coVerify(exactly = 0) { repository.delete(any(), any()) }
    }

    @Test
    fun `add and addAll upsert queries and invalidate service cache`() = runTest {
        authorize(sa = true, administrator = false)
        coEvery { repository.upsert(any(), any(), any()) } returns mockk()
        assertTrue(controller.add(authentication, "app", "query one", "one"))

        authorize(sa = false, administrator = true)
        val queries = listOf(
            PersistedQuery("app", "query two", "two"),
            PersistedQuery("app", "query three", "three"),
        )
        assertTrue(controller.addAll(authentication, "app", queries))

        coVerify { repository.upsert("app", "query one", "one") }
        coVerify { repository.upsert("app", "query two", "two") }
        coVerify { repository.upsert("app", "query three", "three") }
        coVerify(exactly = 2) { service.clearPersistedQueries() }
    }

    @Test
    fun `delete invalidates only when a row was removed`() = runTest {
        authorize(sa = true, administrator = false)
        coEvery { repository.delete("app", "found") } returns 1
        coEvery { repository.delete("app", "missing") } returns 0

        assertTrue(controller.delete(authentication, "app", "found"))
        assertFalse(controller.delete(authentication, "app", "missing"))
        coVerify(exactly = 1) { service.clearPersistedQueries() }
    }
}
