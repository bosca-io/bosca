package bosca.ai.memory

import bosca.ai.memory.graphql.MemoriesController
import bosca.ai.memory.graphql.MemoriesMutationController
import bosca.security.model.Principal
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MemoriesControllerTest {
    private val service = mockk<MemoryService>(relaxed = true)
    private val groups = GroupEvaluator(mockk())
    private val reads = MemoriesController(service, groups)
    private val writes = MemoriesMutationController(service, groups)
    private val principal = Principal(id = UUID.random(), anonymous = false)
    private fun auth(vararg scopes: String) = object : AuthenticationContext(null, null) {
        override fun principal() = ScopedAuthenticatedPrincipal(principal, emptyList(), scopes.toList(), emptySet(), 1)
    }

    @Test fun `text search requires search scope while content scope permits bounded selection`() = runTest {
        assertFailsWith<SecurityException> { reads.search(auth(), MemoryQuery()) }
        assertFailsWith<SecurityException> { reads.search(auth("content:view"), MemoryQuery(text = "preference")) }
        coVerify(exactly = 0) { service.search(any(), any()) }
        reads.search(auth("content:view"), MemoryQuery())
        reads.search(auth("content:view", "search:read"), MemoryQuery(text = "preference"))
        coVerify(exactly = 2) { service.search(any(), any()) }
    }

    @Test fun `mutations check scopes and reject another conversation before editing memory`() = runTest {
        val id = UUID.random()
        val conversation = UUID.random()
        assertFailsWith<SecurityException> { writes.remember(auth("content:view"), RememberMemoryInput(UUID.random(), "Preference")) }
        assertFailsWith<SecurityException> { writes.forget(auth("content:edit"), id, "revision") }
        val authentication = auth("content:edit")
        coEvery { service.reads(authentication, conversation) } throws IllegalArgumentException("Conversation is unavailable")
        assertFailsWith<IllegalArgumentException> { writes.update(authentication, id, "revision", "Correction", conversationId = conversation) }
        coVerify(exactly = 0) { service.remember(any(), any()) }
        coVerify(exactly = 0) { service.forget(any(), any(), any()) }
        coVerify(exactly = 0) { service.update(any(), any(), any(), any(), any()) }
    }
}
