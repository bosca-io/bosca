@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.ci.service

import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.git.ci.parser.PipelineYamlParser
import bosca.git.ci.repository.PipelineRepository
import bosca.git.model.Blob
import bosca.git.model.Pipeline
import bosca.git.model.PipelineTrigger
import bosca.git.ci.toJsonElement
import bosca.git.model.PipelineTriggerType
import bosca.git.model.TreeEntry
import bosca.git.model.TreeEntryType
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.PipelineScheduleService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineServiceImplTest {

    private val pipelineRepository = mockk<PipelineRepository>(relaxed = true)
    private val browseService = mockk<RepositoryBrowseService>()
    private val repositoryService = mockk<bosca.git.service.RepositoryService>(relaxed = true)
    private val scheduleService = mockk<PipelineScheduleService>(relaxed = true)
    private val parser = PipelineYamlParser()
    private lateinit var service: PipelineServiceImpl

    private val repoId = UUID.random()
    private val connection = mockk<ConnectionManager>(relaxed = true)

    private val validYaml = """
        name: Build
        on:
          push:
            branches: [main]
        jobs:
          build:
            steps:
              - name: Checkout
                uses: checkout
    """.trimIndent()

    private val invalidYaml = """
        name: Bad
        on:
          push:
            branches: [main]
        jobs:
          build:
            steps: []
    """.trimIndent()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        coEvery { repositoryService.findById(repoId) } returns bosca.git.model.Repository(
            id = repoId, ownerId = UUID.random(), slug = "repo", name = "Repo",
        )
        coEvery { browseService.resolveRef(repoId, "refs/heads/main") } returns "abc123"
        service = PipelineServiceImpl(
            pipelineRepository,
            browseService,
            parser,
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
            repositoryService,
            scheduleService,
        )
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `syncPipelines creates new pipeline from YAML`() = runTest(connection.asCoroutineContext()) {
        coEvery { browseService.listTree(repoId, "abc123", ".bosca/pipelines") } returns listOf(
            treeEntry("build.yaml")
        )
        coEvery { browseService.readBlob(repoId, "abc123", ".bosca/pipelines/build.yaml") } returns
            blob(validYaml)
        coEvery { pipelineRepository.findByRepositoryAndFilePath(repoId, ".bosca/pipelines/build.yaml") } returns null
        coEvery { pipelineRepository.findByRepository(repoId) } returns emptyList()

        val captured = slot<Pipeline>()
        coEvery { pipelineRepository.create(capture(captured)) } answers {
            captured.captured.copy(id = UUID.random())
        }

        val result = service.syncPipelines(repoId, "refs/heads/main", "abc123")

        assertEquals(1, result.size)
        assertEquals("Build", captured.captured.name)
        assertEquals(repoId, captured.captured.repositoryId)
        assertEquals(".bosca/pipelines/build.yaml", captured.captured.filePath)
        assertTrue(captured.captured.configHash.isNotBlank())
    }

    @Test
    fun `syncPipelines skips unchanged pipeline by config hash`() = runTest(connection.asCoroutineContext()) {
        val configHash = sha256(validYaml)
        val existing = testPipeline(configHash = configHash)

        coEvery { browseService.listTree(repoId, "abc123", ".bosca/pipelines") } returns listOf(
            treeEntry("build.yaml")
        )
        coEvery { browseService.readBlob(repoId, "abc123", ".bosca/pipelines/build.yaml") } returns
            blob(validYaml)
        coEvery { pipelineRepository.findByRepositoryAndFilePath(repoId, ".bosca/pipelines/build.yaml") } returns existing
        coEvery { pipelineRepository.findByRepository(repoId) } returns listOf(existing)

        val result = service.syncPipelines(repoId, "refs/heads/main", "abc123")

        assertEquals(1, result.size)
        coVerify(exactly = 0) { pipelineRepository.update(any()) }
        coVerify(exactly = 0) { pipelineRepository.create(any()) }
    }

    @Test
    fun `syncPipelines discovers files and matches triggers from the pushed commit after the ref moves`() = runTest(connection.asCoroutineContext()) {
        coEvery { browseService.listTree(repoId, "abc123", ".bosca/pipelines") } returns listOf(treeEntry("build.yaml"))
        coEvery { browseService.readBlob(repoId, "abc123", ".bosca/pipelines/build.yaml") } returns blob(validYaml)
        coEvery { browseService.resolveRef(repoId, "refs/heads/main") } returns "later"
        coEvery { browseService.listTree(repoId, "later", ".bosca/pipelines") } returns listOf(treeEntry("later.yaml"))
        coEvery { browseService.readBlob(repoId, "later", ".bosca/pipelines/later.yaml") } returns
            blob(validYaml.replace("branches: [main]", "branches: [develop]"))
        coEvery { pipelineRepository.findByRepositoryAndFilePath(repoId, any()) } returns null
        coEvery { pipelineRepository.findByRepository(repoId) } returns emptyList()
        coEvery { pipelineRepository.create(any()) } answers { firstArg<Pipeline>().copy(id = UUID.random()) }

        val pipeline = service.syncPipelines(repoId, "refs/heads/main", "abc123").single()
        val matched = bosca.git.ci.trigger.TriggerEvaluator().evaluatePush(
            pipeline, bosca.git.model.PushEvent(repoId, "refs/heads/main", "before", "abc123"),
        )

        assertEquals(".bosca/pipelines/build.yaml", pipeline.filePath)
        assertNotNull(matched)
        coVerify { pipelineRepository.create(match { it.filePath == ".bosca/pipelines/build.yaml" && it.deletedAt != null }) }
        coVerify { pipelineRepository.create(match { it.filePath == ".bosca/pipelines/later.yaml" && it.deletedAt == null }) }
        coVerify(exactly = 0) { browseService.listTree(repoId, "refs/heads/main", any()) }
        coVerify(exactly = 0) { browseService.readBlob(repoId, "refs/heads/main", any()) }
    }

    @Test
    fun `syncPipelines updates pipeline when config hash changes`() = runTest(connection.asCoroutineContext()) {
        val existing = testPipeline(configHash = "old-hash")

        coEvery { browseService.listTree(repoId, "abc123", ".bosca/pipelines") } returns listOf(
            treeEntry("build.yaml")
        )
        coEvery { browseService.readBlob(repoId, "abc123", ".bosca/pipelines/build.yaml") } returns
            blob(validYaml)
        coEvery { pipelineRepository.findByRepositoryAndFilePath(repoId, ".bosca/pipelines/build.yaml") } returns existing
        coEvery { pipelineRepository.findByRepository(repoId) } returns listOf(existing)
        coEvery { pipelineRepository.update(any()) } answers { firstArg() }

        val result = service.syncPipelines(repoId, "refs/heads/main", "abc123")

        assertEquals(1, result.size)
        coVerify { pipelineRepository.update(match { it.name == "Build" && it.configHash != "old-hash" }) }
    }

    @Test
    fun `syncPipelines archives removed files while retaining their identities`() = runTest(connection.asCoroutineContext()) {
        val orphaned = testPipeline(filePath = ".bosca/pipelines/old.yaml")

        coEvery { browseService.listTree(repoId, "abc123", ".bosca/pipelines") } returns listOf(
            treeEntry("build.yaml")
        )
        coEvery { browseService.readBlob(repoId, "abc123", ".bosca/pipelines/build.yaml") } returns
            blob(validYaml)
        coEvery { pipelineRepository.findByRepositoryAndFilePath(repoId, ".bosca/pipelines/build.yaml") } returns null
        coEvery { pipelineRepository.findByRepository(repoId) } returns listOf(orphaned)
        coEvery { pipelineRepository.create(any()) } answers { (firstArg() as Pipeline).copy(id = UUID.random()) }

        service.syncPipelines(repoId, "refs/heads/main", "abc123")

        coVerify { scheduleService.deleteByPipeline(orphaned.id) }
        coVerify { pipelineRepository.archive(orphaned.id) }
        coVerify(exactly = 0) { pipelineRepository.delete(any()) }
    }

    @Test
    fun `syncPipelines skips invalid YAML without failing`() = runTest(connection.asCoroutineContext()) {
        coEvery { browseService.listTree(repoId, "abc123", ".bosca/pipelines") } returns listOf(
            treeEntry("build.yaml"),
            treeEntry("bad.yaml")
        )
        coEvery { browseService.readBlob(repoId, "abc123", ".bosca/pipelines/build.yaml") } returns
            blob(validYaml)
        coEvery { browseService.readBlob(repoId, "abc123", ".bosca/pipelines/bad.yaml") } returns
            blob(invalidYaml)
        coEvery { pipelineRepository.findByRepositoryAndFilePath(repoId, any()) } returns null
        coEvery { pipelineRepository.findByRepository(repoId) } returns emptyList()
        coEvery { pipelineRepository.create(any()) } answers { (firstArg() as Pipeline).copy(id = UUID.random()) }

        val result = service.syncPipelines(repoId, "refs/heads/main", "abc123")

        assertEquals(1, result.size)
        assertEquals("Build", result[0].name)
    }

    @Test
    fun `syncPipelines returns empty when no pipeline directory exists`() = runTest(connection.asCoroutineContext()) {
        coEvery { browseService.listTree(repoId, "abc123", ".bosca/pipelines") } returns emptyList()

        val result = service.syncPipelines(repoId, "refs/heads/main", "abc123")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `syncPipelines ignores non-yaml files`() = runTest(connection.asCoroutineContext()) {
        coEvery { browseService.listTree(repoId, "abc123", ".bosca/pipelines") } returns listOf(
            treeEntry("build.yaml"),
            treeEntry("README.md"),
            treeEntry("notes.txt")
        )
        coEvery { browseService.readBlob(repoId, "abc123", ".bosca/pipelines/build.yaml") } returns
            blob(validYaml)
        coEvery { pipelineRepository.findByRepositoryAndFilePath(repoId, any()) } returns null
        coEvery { pipelineRepository.findByRepository(repoId) } returns emptyList()
        coEvery { pipelineRepository.create(any()) } answers { (firstArg() as Pipeline).copy(id = UUID.random()) }

        val result = service.syncPipelines(repoId, "refs/heads/main", "abc123")
        assertEquals(1, result.size)
    }

    @Test
    fun `syncPipelines handles yml extension`() = runTest(connection.asCoroutineContext()) {
        coEvery { browseService.listTree(repoId, "abc123", ".bosca/pipelines") } returns listOf(
            treeEntry("deploy.yml")
        )
        coEvery { browseService.readBlob(repoId, "abc123", ".bosca/pipelines/deploy.yml") } returns
            blob(validYaml)
        coEvery { pipelineRepository.findByRepositoryAndFilePath(repoId, any()) } returns null
        coEvery { pipelineRepository.findByRepository(repoId) } returns emptyList()
        coEvery { pipelineRepository.create(any()) } answers { (firstArg() as Pipeline).copy(id = UUID.random()) }

        val result = service.syncPipelines(repoId, "refs/heads/main", "abc123")
        assertEquals(1, result.size)
    }

    @Test
    fun `findByRepository delegates to repository`() = runTest(connection.asCoroutineContext()) {
        val pipelines = listOf(testPipeline(), testPipeline(filePath = ".bosca/pipelines/deploy.yaml"))
        coEvery { pipelineRepository.findByRepository(repoId) } returns pipelines

        val result = service.findByRepository(repoId)
        assertEquals(2, result.size)
    }

    @Test
    fun `findById returns pipeline when found`() = runTest(connection.asCoroutineContext()) {
        val pipeline = testPipeline()
        coEvery { pipelineRepository.findById(pipeline.id) } returns pipeline

        val result = service.findById(pipeline.id)
        assertNotNull(result)
        assertEquals(pipeline.name, result.name)
    }

    @Test
    fun `findById returns null when not found`() = runTest(connection.asCoroutineContext()) {
        coEvery { pipelineRepository.findById(any()) } returns null
        assertNull(service.findById(UUID.random()))
    }

    @Test
    fun `parseDefinition reads blob and parses YAML`() = runTest(connection.asCoroutineContext()) {
        coEvery { browseService.readBlob(repoId, "main", ".bosca/pipelines/build.yaml") } returns
            blob(validYaml)

        val result = service.parseDefinition(repoId, "main", ".bosca/pipelines/build.yaml")
        assertNotNull(result)
        assertEquals("Build", result.name)
        assertEquals(1, result.jobs.size)
    }

    @Test
    fun `parseDefinition returns null when blob not found`() = runTest(connection.asCoroutineContext()) {
        coEvery { browseService.readBlob(repoId, "main", ".bosca/pipelines/missing.yaml") } returns null

        assertNull(service.parseDefinition(repoId, "main", ".bosca/pipelines/missing.yaml"))
    }

    @Test
    fun `parseDefinition returns null for binary blob`() = runTest(connection.asCoroutineContext()) {
        coEvery { browseService.readBlob(repoId, "main", ".bosca/pipelines/build.yaml") } returns
            Blob(content = null, size = 1000, sha = "abc", isBinary = true)

        assertNull(service.parseDefinition(repoId, "main", ".bosca/pipelines/build.yaml"))
    }

    @Test
    fun `delete delegates to repository`() = runTest(connection.asCoroutineContext()) {
        val id = UUID.random()
        service.delete(id)
        coVerify { scheduleService.deleteByPipeline(id) }
        coVerify { pipelineRepository.delete(id) }
    }

    @Test
    fun `default branch sync mirrors schedule triggers even when YAML is unchanged`() = runTest(connection.asCoroutineContext()) {
        coEvery { browseService.resolveRef(repoId, "refs/heads/main") } returns "abc"
        val yaml = """
            name: Nightly
            on:
              schedule:
                cron: "0 3 * * *"
            jobs:
              build:
                steps:
                  - run: echo build
        """.trimIndent()
        val existing = testPipeline(configHash = sha256(yaml))
        coEvery { repositoryService.findById(repoId) } returns bosca.git.model.Repository(
            id = repoId,
            slug = "repo",
            name = "Repo",
            ownerId = UUID.random(),
            defaultBranch = "main",
        )
        for (commitSha in listOf("abc", "def")) {
            coEvery { browseService.listTree(repoId, commitSha, ".bosca/pipelines") } returns listOf(treeEntry("build.yaml"))
            coEvery { browseService.readBlob(repoId, commitSha, ".bosca/pipelines/build.yaml") } returns blob(yaml)
        }
        coEvery { pipelineRepository.findByRepositoryAndFilePath(repoId, ".bosca/pipelines/build.yaml") } returns existing
        coEvery { pipelineRepository.findByRepository(repoId) } returns listOf(existing)

        service.syncPipelines(repoId, "refs/heads/main", "abc")
        service.syncPipelines(repoId, "refs/heads/feature/x", "def")

        coVerify(exactly = 1) {
            scheduleService.sync(
                existing,
                match { triggers ->
                    triggers.single().type == PipelineTriggerType.SCHEDULE &&
                        triggers.single().cron == "0 3 * * *"
                },
            )
        }
    }

    private val environmentsYaml = """
        name: Release
        on:
          release: true
        environments:
          staging:
            deploy-on: release
          production:
            promotes-from: staging
            approval: required
        jobs:
          deploy-staging:
            environment: staging
            steps:
              - name: Deploy
                run: echo deploy
    """.trimIndent()

    @Test
    fun `a default-branch sync announces declared environments and a feature-branch sync does not`() = runTest(connection.asCoroutineContext()) {
        val pubSub = mockk<bosca.pubsub.PubSubService>(relaxed = true)
        bosca.di.provides<bosca.pubsub.PubSubService>(singleton = true) { pubSub }
        coEvery { repositoryService.findById(repoId) } returns bosca.git.model.Repository(
            id = repoId, slug = "repo", name = "Repo", ownerId = UUID.random(),
        )
        for (commitSha in listOf("abc123", "def456")) {
            coEvery { browseService.listTree(repoId, commitSha, ".bosca/pipelines") } returns listOf(treeEntry("release.yaml"))
            coEvery { browseService.readBlob(repoId, commitSha, ".bosca/pipelines/release.yaml") } returns blob(environmentsYaml)
        }
        coEvery { pipelineRepository.findByRepositoryAndFilePath(repoId, ".bosca/pipelines/release.yaml") } returns null
        coEvery { pipelineRepository.findByRepository(repoId) } returns emptyList()
        val pipelineId = UUID.random()
        coEvery { pipelineRepository.create(any()) } answers { firstArg<Pipeline>().copy(id = pipelineId) }

        service.syncPipelines(repoId, "refs/heads/main", "abc123")
        coVerify(exactly = 1) {
            pubSub.publish(
                "bosca.git.pipeline.environments",
                any<kotlinx.serialization.SerializationStrategy<bosca.git.model.PipelineEnvironmentsSynced>>(),
                match<bosca.git.model.PipelineEnvironmentsSynced> {
                    it.repositoryId == repoId && it.pipelineId == pipelineId &&
                        it.environments.keys == setOf("staging", "production") &&
                        it.environments.getValue("production").approval &&
                        it.environments.getValue("production").promotesFrom == "staging"
                },
            )
        }

        // A feature branch's YAML must never rewrite a program's environment topology.
        service.syncPipelines(repoId, "refs/heads/feature/x", "def456")
        coVerify(exactly = 1) { pubSub.publish("bosca.git.pipeline.environments", any(), any<Any>()) }
    }

    @Test
    fun `stale trigger metadata never overwrites the current catalog or schedules`() = runTest(connection.asCoroutineContext()) {
        val currentYaml = validYaml.replace("name: Build", "name: Current").replace("branches: [main]", "branches: [develop]")
        val current = testPipeline(configHash = sha256(currentYaml)).copy(
            name = "Current",
            triggers = listOf(PipelineTrigger(PipelineTriggerType.PUSH, branches = listOf("develop"))).toJsonElement(),
        )
        val added = testPipeline(filePath = ".bosca/pipelines/deploy.yaml", configHash = sha256(validYaml))
        coEvery { browseService.resolveRef(repoId, "refs/heads/main") } returns "newer"
        coEvery { browseService.listTree(repoId, "newer", ".bosca/pipelines") } returns
            listOf(treeEntry("build.yaml"), treeEntry("deploy.yaml"))
        coEvery { browseService.readBlob(repoId, "newer", current.filePath) } returns blob(currentYaml)
        coEvery { browseService.readBlob(repoId, "newer", added.filePath) } returns blob(validYaml)
        coEvery { browseService.listTree(repoId, "older", ".bosca/pipelines") } returns listOf(treeEntry("build.yaml"))
        coEvery { browseService.readBlob(repoId, "older", current.filePath) } returns blob(validYaml)
        coEvery { pipelineRepository.findByRepositoryAndFilePath(repoId, current.filePath) } returns current
        coEvery { pipelineRepository.findByRepositoryAndFilePath(repoId, added.filePath) } returns added
        coEvery { pipelineRepository.findByRepository(repoId) } returns listOf(current, added)

        val triggered = service.syncPipelines(repoId, "refs/heads/main", "older").single()

        assertEquals(current.id, triggered.id)
        assertEquals("Build", triggered.name)
        assertNotNull(bosca.git.ci.trigger.TriggerEvaluator().evaluatePush(
            triggered, bosca.git.model.PushEvent(repoId, "refs/heads/main", "before", "older"),
        ))
        coVerify { scheduleService.sync(current, match { it.single().branches == listOf("develop") }) }
        coVerify(exactly = 0) { pipelineRepository.update(any()) }
        coVerify(exactly = 0) { pipelineRepository.create(any()) }
        coVerify(exactly = 0) { pipelineRepository.archive(any()) }
        coVerify(exactly = 0) { pipelineRepository.delete(any()) }
    }

    @Test
    fun `stale default branch delivery announces only current environments`() = runTest(connection.asCoroutineContext()) {
        val pubSub = mockk<bosca.pubsub.PubSubService>(relaxed = true)
        bosca.di.provides<bosca.pubsub.PubSubService> { pubSub }
        val currentYaml = environmentsYaml.replace("production:", "live:")
        val current = testPipeline(filePath = ".bosca/pipelines/release.yaml", configHash = sha256(currentYaml))
        coEvery { browseService.resolveRef(repoId, "refs/heads/main") } returns "newer"
        for ((sha, yaml) in listOf("newer" to currentYaml, "older" to environmentsYaml)) {
            coEvery { browseService.listTree(repoId, sha, ".bosca/pipelines") } returns listOf(treeEntry("release.yaml"))
            coEvery { browseService.readBlob(repoId, sha, current.filePath) } returns blob(yaml)
        }
        coEvery { pipelineRepository.findByRepositoryAndFilePath(repoId, current.filePath) } returns current
        coEvery { pipelineRepository.findByRepository(repoId) } returns listOf(current)

        service.syncPipelines(repoId, "refs/heads/main", "older")

        coVerify(exactly = 1) {
            pubSub.publish(
                "bosca.git.pipeline.environments",
                any<kotlinx.serialization.SerializationStrategy<bosca.git.model.PipelineEnvironmentsSynced>>(),
                match<bosca.git.model.PipelineEnvironmentsSynced> {
                    it.pipelineId == current.id && it.environments.keys == setOf("staging", "live")
                },
            )
        }
    }

    @Test
    fun `a reappearing file restores the same archived pipeline identity`() = runTest(connection.asCoroutineContext()) {
        val archived = testPipeline(configHash = sha256(validYaml)).copy(deletedAt = bosca.serialization.OffsetDateTime.now())
        coEvery { browseService.listTree(repoId, "abc123", ".bosca/pipelines") } returns listOf(treeEntry("build.yaml"))
        coEvery { browseService.readBlob(repoId, "abc123", archived.filePath) } returns blob(validYaml)
        coEvery { pipelineRepository.findByRepositoryAndFilePath(repoId, archived.filePath) } returns archived
        coEvery { pipelineRepository.findByRepository(repoId) } returns emptyList()
        coEvery { pipelineRepository.update(any()) } answers { firstArg() }

        val restored = service.syncPipelines(repoId, "refs/heads/main", "abc123").single()

        assertEquals(archived.id, restored.id)
        assertNull(restored.deletedAt)
        coVerify { pipelineRepository.update(match { it.id == archived.id && it.deletedAt == null }) }
        coVerify(exactly = 0) { pipelineRepository.create(any()) }
    }

    @Test
    fun `a failed catalog read propagates without archiving pipelines`() = runTest(connection.asCoroutineContext()) {
        coEvery { browseService.listTree(repoId, "abc123", ".bosca/pipelines") } throws IllegalStateException("storage unavailable")

        assertFailsWith<IllegalStateException> { service.syncPipelines(repoId, "refs/heads/main", "abc123") }

        coVerify(exactly = 0) { pipelineRepository.archive(any()) }
        coVerify(exactly = 0) { pipelineRepository.delete(any()) }
    }

    private fun testPipeline(
        id: UUID = UUID.random(),
        filePath: String = ".bosca/pipelines/build.yaml",
        configHash: String = "test-hash"
    ) = Pipeline(
        id = id,
        repositoryId = repoId,
        filePath = filePath,
        name = "Build",
        triggers = listOf(PipelineTrigger(type = PipelineTriggerType.PUSH, branches = listOf("main"))).toJsonElement(),
        configHash = configHash
    )

    private fun treeEntry(name: String) = TreeEntry(
        name = name,
        path = ".bosca/pipelines/$name",
        type = TreeEntryType.BLOB,
        mode = 0x100644,
        sha = "abc123",
        size = 100
    )

    private fun blob(content: String) = Blob(
        content = content,
        size = content.length.toLong(),
        sha = "abc123",
        isBinary = false
    )

    private fun sha256(content: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
