package bosca.communications.service

import bosca.communications.model.BmlMessageProject
import bosca.communications.model.MessageBmlTemplate
import bosca.communications.repository.BmlMessageProjectRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The registry's contract: registration + pin/rollback, and resolution — references
 * pass through and pick up the project's pin when registered.
 */
class BmlMessageRegistryServiceImplTest {

    private val projects = FakeProjects()
    private val service = BmlMessageRegistryServiceImpl(projects)
    private val repoId = bosca.serialization.UUID.random()

    @Test
    fun `registration is idempotent and pinning is data-driven rollback`() = runBlocking {
        service.registerProject("acme", description = "Acme emails", repositoryId = repoId)
        val pinned = service.pinProjectVersion("acme", "20260701-abc")
        assertEquals("20260701-abc", pinned.pinnedVersion)

        // Re-registering updates metadata without disturbing the pin.
        val updated = service.registerProject("acme", description = "Acme transactional emails")
        assertEquals("Acme transactional emails", updated.description)
        assertEquals("20260701-abc", service.getProject("acme")?.pinnedVersion)

        // Clearing the pin returns sends to the active (latest) version.
        assertNull(service.pinProjectVersion("acme", null).pinnedVersion)
    }

    @Test
    fun `pinning an unregistered project fails typed`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { service.pinProjectVersion("ghost", "1.0") }
    }

    @Test
    fun `resolution passes the reference through, unregistered projects simply carry no pin`() = runBlocking {
        val unregistered = service.resolve(MessageBmlTemplate(project = "acme", templateKey = "welcome"))
        assertEquals("acme", unregistered.project)
        assertEquals("welcome", unregistered.templateKey)
        assertNull(unregistered.version)

        service.registerProject("acme")
        service.pinProjectVersion("acme", "2.0")
        assertEquals("2.0", service.resolve(MessageBmlTemplate(project = "acme", templateKey = "welcome")).version)
    }

    @Test
    fun `removal reports whether anything existed`() = runBlocking {
        service.registerProject("acme")
        assertTrue(service.removeProject("acme"))
        assertFalse(service.removeProject("acme"))
    }

    /** In-memory [BmlMessageProjectRepository] mirroring the SQL upsert/pin semantics. */
    private class FakeProjects : BmlMessageProjectRepository {
        val rows = LinkedHashMap<String, BmlMessageProject>()

        override suspend fun getAll(): List<BmlMessageProject> = rows.values.toList()
        override suspend fun get(projectKey: String): BmlMessageProject? = rows[projectKey]
        override suspend fun upsert(key: String, description: String?, repositoryId: bosca.serialization.UUID?): BmlMessageProject {
            val next = (rows[key] ?: BmlMessageProject(key))
                .copy(description = description, repositoryId = repositoryId)
            rows[key] = next
            return next
        }

        override suspend fun setPinnedVersion(projectKey: String, version: String?): BmlMessageProject? {
            val next = rows[projectKey]?.copy(pinnedVersion = version) ?: return null
            rows[projectKey] = next
            return next
        }

        override suspend fun delete(projectKey: String): Int = if (rows.remove(projectKey) != null) 1 else 0
    }
}
