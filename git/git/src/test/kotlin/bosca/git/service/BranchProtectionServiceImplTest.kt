package bosca.git.service

import bosca.git.model.BranchProtectionRule
import bosca.git.repository.BranchProtectionRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers [BranchProtectionServiceImpl]: repository delegation and glob rule matching. */
class BranchProtectionServiceImplTest {

    private val repository = mockk<BranchProtectionRepository>(relaxed = true)
    private val service = BranchProtectionServiceImpl(repository)
    private val repositoryId = UUID.random()

    @Test
    fun `crud operations delegate to the repository`() = runTest {
        val rule = BranchProtectionRule(id = UUID.random(), repositoryId = repositoryId, pattern = "main")

        service.findByRepository(repositoryId)
        coVerify { repository.findByRepository(repositoryId) }

        service.findById(rule.id)
        coVerify { repository.findById(rule.id) }

        service.create(rule)
        coVerify { repository.create(rule) }

        coEvery { repository.update(rule) } returns rule
        assertEquals(rule, service.update(rule))

        service.delete(rule.id)
        coVerify { repository.delete(rule.id) }
    }

    @Test
    fun `update throws when the rule is missing`() = runTest {
        val rule = BranchProtectionRule(id = UUID.random(), repositoryId = repositoryId, pattern = "main")
        coEvery { repository.update(rule) } returns null
        try {
            service.update(rule)
            kotlin.test.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `findMatchingRule returns the first rule whose glob matches`() = runTest {
        val releaseRule = BranchProtectionRule(id = UUID.random(), repositoryId = repositoryId, pattern = "release/*")
        val mainRule = BranchProtectionRule(id = UUID.random(), repositoryId = repositoryId, pattern = "main")
        coEvery { repository.findPatternsByRepository(repositoryId) } returns listOf("main", "release/*")
        coEvery { repository.findByRepositoryAndPattern(repositoryId, "main") } returns mainRule
        coEvery { repository.findByRepositoryAndPattern(repositoryId, "release/*") } returns releaseRule

        assertEquals(mainRule, service.findMatchingRule(repositoryId, "main"))
        assertEquals(releaseRule, service.findMatchingRule(repositoryId, "release/1.0"))
        assertEquals(null, service.findMatchingRule(repositoryId, "feature/x"))
    }

    @Test
    fun `unprotected branch does not materialize unrelated rules`() = runTest {
        coEvery { repository.findPatternsByRepository(repositoryId) } returns listOf("main")

        assertEquals(null, service.findMatchingRule(repositoryId, "kjb/test"))

        coVerify(exactly = 0) { repository.findByRepositoryAndPattern(any(), any()) }
        coVerify(exactly = 0) { repository.findByRepository(repositoryId) }
    }
}
