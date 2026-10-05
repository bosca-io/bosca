package bosca.git.service

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.CreateRepositoryInput
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [RepositoryInitializerImpl]: the no-op fast path, README/.gitignore/
 * LICENSE seeding (with template substitution), unknown-template tolerance, and
 * the initial-commit ref + HEAD wiring — all against a real in-memory repo.
 */
class RepositoryInitializerImplTest {

    private val dfsManager = mockk<BoscaDfsRepositoryManager>()
    private val lockFactory = mockk<DistributedLockFactory>()
    private val lock = mockk<DistributedLock>(relaxed = true)
    private val initializer = RepositoryInitializerImpl(dfsManager, lockFactory)

    private val repositoryId = UUID.random()
    private lateinit var gitRepo: InMemoryRepository

    @BeforeTest
    fun setup() {
        gitRepo = InMemoryRepository(DfsRepositoryDescription("init"))
        every { dfsManager.open(repositoryId) } returns gitRepo
        coEvery { lockFactory.create(any()) } returns lock
        coEvery { lock.acquire(any(), any(), any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true
    }

    @AfterTest
    fun teardown() = gitRepo.close()

    private fun input(
        readme: Boolean = false,
        gitignore: String? = null,
        license: String? = null,
        description: String? = null,
        branch: String = "main",
    ) = CreateRepositoryInput(
        slug = "r", name = "My Repo", description = description, ownerId = UUID.random(),
        defaultBranch = branch, initializeWithReadme = readme,
        gitignoreTemplate = gitignore, licenseTemplate = license,
    )

    private fun fileText(path: String, branch: String = "main"): String? {
        val ref = gitRepo.refDatabase.findRef("refs/heads/$branch") ?: return null
        val commit = RevWalk(gitRepo).use { it.parseCommit(ref.objectId) }
        val walk = TreeWalk.forPath(gitRepo, path, commit.tree) ?: return null
        return String(gitRepo.objectDatabase.open(walk.getObjectId(0)).cachedBytes)
    }

    @Test
    fun `links HEAD but writes no content when no seed content is requested`() = runTest {
        assertEquals(0L, initializer.initialize(repositoryId, input()))
        assertNull(gitRepo.refDatabase.findRef("refs/heads/main"))
        // HEAD must still point at the (unborn) default branch so protocol-v2
        // ls-refs advertises it once the first push lands — single-branch and
        // shallow clones select their branch from the advertised HEAD.
        val head = gitRepo.refDatabase.exactRef("HEAD")
        assertNotNull(head, "HEAD symref must exist on an empty repository")
        assertTrue(head.isSymbolic, "HEAD must be symbolic")
        assertEquals("refs/heads/main", head.target.name)
    }

    @Test
    fun `empty repository links HEAD to a custom default branch`() = runTest {
        assertEquals(0L, initializer.initialize(repositoryId, input(branch = "trunk")))
        val head = gitRepo.refDatabase.exactRef("HEAD")
        assertNotNull(head)
        assertEquals("refs/heads/trunk", head.target.name)
    }

    @Test
    fun `seeds a readme with the name and description`() = runTest {
        val bytes = initializer.initialize(repositoryId, input(readme = true, description = "does things"))

        assertTrue(bytes > 0)
        val readme = assertNotNull(fileText("README.md"))
        assertTrue(readme.contains("# My Repo"))
        assertTrue(readme.contains("does things"))
        // HEAD links to the default branch.
        assertEquals("refs/heads/main", gitRepo.refDatabase.findRef("HEAD")?.target?.name)
    }

    @Test
    fun `initial commit leaves the request thread`() = runTest {
        val requestThread = Thread.currentThread()
        var openThread: Thread? = null
        every { dfsManager.open(repositoryId) } answers {
            openThread = Thread.currentThread()
            gitRepo
        }

        initializer.initialize(repositoryId, input(readme = true))

        assertNotSame(requestThread, assertNotNull(openThread))
        assertNotNull(fileText("README.md"))
    }

    @Test
    fun `readme omits the description block when absent`() = runTest {
        initializer.initialize(repositoryId, input(readme = true))
        val readme = assertNotNull(fileText("README.md"))
        assertEquals("# My Repo\n", readme)
    }

    @Test
    fun `seeds a gitignore from a known template`() = runTest {
        initializer.initialize(repositoryId, input(readme = true, gitignore = "Kotlin"))
        assertNotNull(fileText(".gitignore"))
    }

    @Test
    fun `seeds a license with year and name substituted`() = runTest {
        initializer.initialize(repositoryId, input(readme = true, license = "MIT"))
        val license = assertNotNull(fileText("LICENSE"))
        assertTrue(license.contains("My Repo"), "fullname placeholder not substituted")
        assertTrue(license.contains(java.time.Year.now().toString()), "year placeholder not substituted")
        assertTrue(!license.contains("[fullname]") && !license.contains("[year]"))
    }

    @Test
    fun `unknown templates are skipped without failing`() = runTest {
        val bytes = initializer.initialize(repositoryId, input(readme = true, gitignore = "NoSuch", license = "NoSuch"))
        assertTrue(bytes > 0) // README still written
        assertNull(fileText(".gitignore"))
        assertNull(fileText("LICENSE"))
    }

    @Test
    fun `initializes onto a custom default branch`() = runTest {
        initializer.initialize(repositoryId, input(readme = true, branch = "trunk"))
        assertNotNull(fileText("README.md", branch = "trunk"))
        assertEquals("refs/heads/trunk", gitRepo.refDatabase.findRef("HEAD")?.target?.name)
    }
}
