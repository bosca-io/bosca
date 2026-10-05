package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.dependency.DependencyDeclaration
import bosca.workops.repository.DependencyDeclarationRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [DependencyGraphServiceImpl.buildOrder]: declared providers come before their consumers within a
 * release's project set; unrelated projects keep the caller's (deployment) order; a cycle fails loudly.
 */
class DependencyGraphBuildOrderTest {

    private val repository = mockk<DependencyDeclarationRepository>()
    private val service = DependencyGraphServiceImpl(repository)

    private val api = UUID.random()
    private val web = UUID.random()
    private val shared = UUID.random()

    private fun declares(consumer: UUID, vararg providers: UUID) {
        coEvery { repository.listByConsumer(consumer) } returns providers.map {
            DependencyDeclaration(
                consumerProjectId = consumer, providerProjectId = it,
                providerVersionConstraint = "*", dependencyType = bosca.workops.model.dependency.DependencyType.BUILD,
            )
        }
    }

    @Test
    fun `providers order before their consumers, transitively`() = runTest {
        // web depends on api, api depends on shared — input deliberately reversed.
        declares(web, api)
        declares(api, shared)
        declares(shared)

        assertEquals(listOf(shared, api, web), service.buildOrder(listOf(web, api, shared)))
    }

    @Test
    fun `projects with no dependencies keep the input (deployment) order`() = runTest {
        declares(api)
        declares(web)
        declares(shared)

        assertEquals(listOf(web, shared, api), service.buildOrder(listOf(web, shared, api)))
    }

    @Test
    fun `a provider outside the release does not constrain the order`() = runTest {
        val external = UUID.random()
        declares(web, external)
        declares(api)

        assertEquals(listOf(web, api), service.buildOrder(listOf(web, api)))
    }

    @Test
    fun `self dependencies do not constrain the order`() = runTest {
        declares(web, web)
        declares(api)

        assertEquals(listOf(web, api), service.buildOrder(listOf(web, api)))
    }

    @Test
    fun `a consumer waits for every provider`() = runTest {
        declares(web, api, shared)
        declares(api)
        declares(shared)

        assertEquals(listOf(api, shared, web), service.buildOrder(listOf(web, api, shared)))
    }

    @Test
    fun `unrelated projects keep input order around a constrained pair`() = runTest {
        declares(web, api)
        declares(api)
        declares(shared)

        // shared is unconstrained: it stays where the deployment order put it (first).
        assertEquals(listOf(shared, api, web), service.buildOrder(listOf(shared, web, api)))
    }

    @Test
    fun `a dependency cycle fails loudly rather than inventing an order`() = runTest {
        declares(web, api)
        declares(api, web)

        val e = assertFailsWith<IllegalStateException> { service.buildOrder(listOf(web, api)) }
        assertTrue("cycle" in (e.message ?: ""), e.message)
    }

    @Test
    fun `a single project needs no repository access at all`() = runTest {
        assertEquals(listOf(api), service.buildOrder(listOf(api)))
    }
}
