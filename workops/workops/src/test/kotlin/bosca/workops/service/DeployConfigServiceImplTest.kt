package bosca.workops.service

import bosca.git.service.CommitFileInput
import bosca.git.service.CommitFileResult
import bosca.git.service.RepositoryWriteService
import bosca.serialization.UUID
import bosca.workops.deploy.DeployTargetKind
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentTargetType
import bosca.workops.model.project.Project
import bosca.workops.model.project.ProjectRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [DeployConfigServiceImpl]: resolves `.bosca/deploy.yaml` from a project's repositories at candidate refs. */
class DeployConfigServiceImplTest {

    private val projectRepositories = mockk<ProjectRepositoryService>()
    private val repositoryWrite = mockk<RepositoryWriteService>()
    private val projectService = mockk<ProjectService>()
    private val environmentService = mockk<EnvironmentService>()
    private val service = DeployConfigServiceImpl(projectRepositories, repositoryWrite, projectService, environmentService)

    private val projectId = UUID.random()
    private val repoId = UUID.random()
    private val tagRef = "refs/tags/v1.4.0"
    private val mainRef = "refs/heads/main"

    private val yaml = """
        environments:
          staging:
            target: helm
            config:
              clusterId: "c1"
              releaseName: "my-api"
              namespace: "staging"
          play-production:
            target: google_play
            artifact:
              type: android-aar
              namespace: bosca-raw
              coordinate: "app:${'$'}{{ version }}"
            config:
              packageName: "io.example.app"
              track: "production"
    """.trimIndent()

    private fun stubRepos(vararg repositoryIds: UUID) {
        coEvery { projectRepositories.list(projectId) } returns
            repositoryIds.map { ProjectRepository(projectId = projectId, repositoryId = it) }
    }

    @Test
    fun `reads the config at the release tag and returns the environment's declaration`() = runTest {
        stubRepos(repoId)
        coEvery { repositoryWrite.readFile(repoId, tagRef, ".bosca/deploy.yaml") } returns yaml

        val config = service.forEnvironment(projectId, "staging", listOf(tagRef, mainRef))

        assertEquals(DeployTargetKind.HELM, config?.targetKind())
        assertEquals(JsonPrimitive("my-api"), (config?.config as JsonObject)["releaseName"])
        // The resolver stamps where the file came from, so adapters can resolve repo-relative
        // references (e.g. the values file) without UUIDs in the file.
        assertEquals(repoId.toString(), config.sourceRepositoryId)
    }

    @Test
    fun `falls back to the default branch when the tag has no config`() = runTest {
        stubRepos(repoId)
        coEvery { repositoryWrite.readFile(repoId, tagRef, ".bosca/deploy.yaml") } returns null
        coEvery { repositoryWrite.readFile(repoId, mainRef, ".bosca/deploy.yaml") } returns yaml

        val config = service.forEnvironment(projectId, "play-production", listOf(tagRef, mainRef))

        assertEquals(DeployTargetKind.GOOGLE_PLAY, config?.targetKind())
        assertEquals("bosca-raw", config?.artifact?.namespace)
        assertEquals("app:${'$'}{{ version }}", config?.artifact?.coordinate)
    }

    @Test
    fun `the first config file found is authoritative — an undeclared environment is null, not a fall-through`() = runTest {
        val second = UUID.random()
        stubRepos(repoId, second)
        coEvery { repositoryWrite.readFile(repoId, tagRef, ".bosca/deploy.yaml") } returns yaml
        // The second repo would declare "production", but it must never be consulted.

        assertNull(service.forEnvironment(projectId, "production", listOf(tagRef)))
    }

    @Test
    fun `no repository declares a config — null`() = runTest {
        stubRepos(repoId)
        coEvery { repositoryWrite.readFile(any(), any(), any()) } returns null
        assertNull(service.forEnvironment(projectId, "staging", listOf(tagRef, mainRef)))
    }

    @Test
    fun `environment names are case-insensitive`() = runTest {
        stubRepos(repoId)
        coEvery { repositoryWrite.readFile(repoId, tagRef, ".bosca/deploy.yaml") } returns yaml
        assertEquals(DeployTargetKind.HELM, service.forEnvironment(projectId, "Staging", listOf(tagRef))?.targetKind())
    }

    @Test
    fun `a malformed config fails loudly rather than deploying defaults`() = runTest {
        stubRepos(repoId)
        coEvery { repositoryWrite.readFile(repoId, tagRef, ".bosca/deploy.yaml") } returns "environments: ["
        assertFailsWith<IllegalStateException> { service.forEnvironment(projectId, "staging", listOf(tagRef)) }
    }

    @Test
    fun `read rejects every malformed deploy config structure with source context`() = runTest {
        val malformed = listOf(
            "- not-a-mapping",
            "root: true",
            "environments:\n  production: not-a-mapping",
            "environments:\n  production:\n    targets: not-a-list",
            "environments:\n  production:\n    targets: [not-a-mapping]",
            "environments:\n  production:\n    artifact: not-a-mapping",
            "environments:\n  production:\n    artifact: { type: docker }",
            "environments:\n  production:\n    targets:\n      - artifact: not-a-mapping",
        )
        for (content in malformed) {
            coEvery { repositoryWrite.readFile(repoId, tagRef, ".bosca/deploy.yaml") } returns content
            val failure = assertFailsWith<IllegalStateException> { service.read(repoId, tagRef) }
            assertTrue(repoId.toString() in failure.message.orEmpty(), failure.message)
            assertTrue(tagRef in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun `read converts nested yaml scalars lists maps defaults and nulls to typed json`() = runTest {
        val content = """
            environments:
              production:
                config:
                  text: value
                  enabled: true
                  count: 7
                  large: 2147483648
                  ratio: 1.5
                  huge: 1234567890123456789012345
                  date: 2026-08-19
                  missing: null
                  list: [1, false, value]
                  nested: { key: value }
                targets:
                  - artifact: { coordinate: "server:${'$'}{{ version }}" }
        """.trimIndent()
        coEvery { repositoryWrite.readFile(repoId, tagRef, ".bosca/deploy.yaml") } returns content

        val config = service.read(repoId, tagRef) ?: error("missing config")
        val environment = config.environments.getValue("production")
        val values = environment.config as JsonObject
        assertEquals(JsonPrimitive("value"), values["text"])
        assertEquals(JsonPrimitive(true), values["enabled"])
        assertEquals(JsonPrimitive(7), values["count"])
        assertEquals(JsonPrimitive(2_147_483_648L), values["large"])
        assertEquals(JsonPrimitive(1.5), values["ratio"])
        assertTrue(values["huge"] is JsonPrimitive)
        assertTrue(values["date"] is JsonPrimitive)
        assertEquals(JsonNull, values["missing"])
        assertTrue(values["list"] is JsonArray)
        assertTrue(values["nested"] is JsonObject)
        assertEquals("helm", environment.targets.single().target)
        assertNull(environment.targets.single().artifact?.type)

        coEvery { repositoryWrite.readFile(repoId, mainRef, ".bosca/deploy.yaml") } returns null
        assertNull(service.read(repoId, mainRef))
    }

    // ── createDeployConfig (the starter-file setup) ────────────────────────────

    private val programId = UUID.random()

    private fun stubProject() {
        coEvery { projectService.getById(projectId) } returns
            Project(id = projectId, programId = programId, key = "API", name = "My API", ownerProfileId = UUID.NIL)
    }

    private fun env(name: String, target: EnvironmentTargetType = EnvironmentTargetType.GENERIC, ref: String? = null, ephemeral: Boolean = false) =
        Environment(id = UUID.random(), programId = programId, key = name.lowercase(), name = name, targetType = target, targetRef = ref, ephemeral = ephemeral)

    @Test
    fun `creates a starter config with one stanza per environment, adapter chosen by target type, and commits it`() = runTest {
        stubProject()
        stubRepos(repoId)
        coEvery { repositoryWrite.readFile(repoId, "main", ".bosca/deploy.yaml") } returns null
        coEvery { environmentService.listByProgram(programId) } returns listOf(
            env("development"),
            env("play-prod", EnvironmentTargetType.PLAY_TRACK, ref = "production"),
            env("flight", EnvironmentTargetType.TESTFLIGHT, ref = "beta-testers"),
            env("pr-preview", ephemeral = true), // never scaffolded — created/destroyed dynamically
        )
        val committed = slot<CommitFileInput>()
        coEvery { repositoryWrite.commitFile(capture(committed)) } answers {
            CommitFileResult(commitSha = "abc123", branch = firstArg<CommitFileInput>().branch, path = firstArg<CommitFileInput>().path)
        }

        val result = service.createDeployConfig(projectId, repoId, "Developer", "developer@example.com")

        assertEquals(".bosca/deploy.yaml", result.path)
        assertEquals("abc123", result.commitSha)
        assertEquals("Developer", committed.captured.authorName)
        val content = committed.captured.content
        assertEquals(result.content, content)
        // Helm stanza derives names from the project key and environment.
        assertTrue("development:" in content && "target: helm-values" in content, content)
        assertTrue("releaseName: \"api\"" in content && "namespace: \"development\"" in content, content)
        // The env stanzas carry pure routing — no values keys anywhere: values ship as helm-values
        // registry artifacts and are applied by the helm-values deploy target.
        assertTrue("valuesRepositoryId: " !in content && "valuesPath: " !in content && "versionPaths:" !in content, content)
        assertTrue("helmValues:" !in content, content)
        // Store stanzas pick their adapters and carry the targetRef + secret-name placeholders.
        assertTrue("target: google_play" in content && "track: \"production\"" in content && "play-sa" in content, content)
        assertTrue("type: android-aar" in content && "packageName:" in content, content)
        assertTrue("versionCode:" !in content, content)
        assertTrue("target: app_store" in content && "testflightGroups: [\"beta-testers\"]" in content && "asc-key" in content, content)
        // The ephemeral preview environment is not scaffolded.
        assertTrue("pr-preview" !in content, content)
        // The scaffold must parse back through the reader it feeds.
        coEvery { repositoryWrite.readFile(repoId, tagRef, ".bosca/deploy.yaml") } returns content
        assertEquals(DeployTargetKind.HELM_VALUES, service.forEnvironment(projectId, "development", listOf(tagRef))?.targetKind())
    }

    @Test
    fun `never overwrites an existing deploy config`() = runTest {
        stubProject()
        stubRepos(repoId)
        coEvery { repositoryWrite.readFile(repoId, "main", ".bosca/deploy.yaml") } returns yaml
        val e = assertFailsWith<IllegalStateException> { service.createDeployConfig(projectId, repoId, "Developer", "developer@example.com") }
        assertTrue("already has" in (e.message ?: ""), e.message)
    }

    @Test
    fun `fails when the repository is not linked to the project`() = runTest {
        stubProject()
        stubRepos(repoId)
        val e = assertFailsWith<IllegalArgumentException> { service.createDeployConfig(projectId, UUID.random(), "Developer", "developer@example.com") }
        assertTrue("not linked" in (e.message ?: ""), e.message)
    }

    @Test
    fun `fails when the program has no environments to scaffold from`() = runTest {
        stubProject()
        stubRepos(repoId)
        coEvery { repositoryWrite.readFile(repoId, "main", ".bosca/deploy.yaml") } returns null
        coEvery { environmentService.listByProgram(programId) } returns emptyList()
        val e = assertFailsWith<IllegalStateException> { service.createDeployConfig(projectId, repoId, "Developer", "developer@example.com") }
        assertTrue("no environments" in (e.message ?: ""), e.message)
    }

    @Test
    fun `starter config covers app store and default play track and rejects a missing project`() = runTest {
        val missingProjectId = UUID.random()
        coEvery { projectService.getById(missingProjectId) } returns null
        assertFailsWith<IllegalStateException> {
            service.createDeployConfig(missingProjectId, repoId, "Developer", "developer@example.com")
        }

        stubProject()
        stubRepos(repoId)
        coEvery { repositoryWrite.readFile(repoId, "main", ".bosca/deploy.yaml") } returns null
        coEvery { environmentService.listByProgram(programId) } returns listOf(
            env("play", EnvironmentTargetType.PLAY_TRACK),
            env("play-blank", EnvironmentTargetType.PLAY_TRACK, ref = ""),
            env("store", EnvironmentTargetType.APP_STORE),
        )
        coEvery { repositoryWrite.commitFile(any()) } answers {
            val input = firstArg<CommitFileInput>()
            CommitFileResult("def456", input.branch, input.path)
        }

        val content = service.createDeployConfig(projectId, repoId, "Developer", "developer@example.com").content
        assertTrue("track: \"internal\"" in content, content)
        assertTrue("target: app_store" in content, content)
        assertTrue("testflightGroups" !in content, content)
    }
}
