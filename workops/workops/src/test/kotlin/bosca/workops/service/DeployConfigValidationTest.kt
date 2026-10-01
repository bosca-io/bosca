@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.service

import bosca.di.provides
import bosca.git.model.CommitStatusState
import bosca.git.model.PushEvent
import bosca.git.service.CommitStatusService
import bosca.git.service.RepositoryWriteService
import bosca.serialization.UUID
import bosca.workops.deploy.DeployConfigService
import bosca.workops.deploy.DeployTarget
import bosca.workops.model.environment.Environment
import bosca.workops.model.project.Project
import bosca.workops.model.project.ProjectRepository as ProjectRepositoryLink
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeployConfigValidationTest {

    private val projectRepositories = mockk<ProjectRepositoryService>(relaxed = true)
    private val repositoryWrite = mockk<RepositoryWriteService>()
    private val projectService = mockk<ProjectService>(relaxed = true)
    private val environmentService = mockk<EnvironmentService>()

    private val service = DeployConfigServiceImpl(
        projectRepositories, repositoryWrite, projectService, environmentService,
    )

    private val repositoryId = UUID.random()
    private val programId = UUID.random()

    @BeforeTest
    fun setup() {
        bosca.di.ProviderRegistry.clear()
        val projectId = UUID.random()
        coEvery { projectRepositories.listByRepository(repositoryId) } returns listOf(
            ProjectRepositoryLink(id = UUID.random(), projectId = projectId, repositoryId = repositoryId),
        )
        coEvery { projectService.getById(projectId) } returns
            Project(id = projectId, programId = programId, key = "P", name = "P", ownerProfileId = UUID.random())
        coEvery { environmentService.listByProgram(programId) } returns listOf(
            Environment(id = UUID.random(), programId = programId, key = "staging", name = "Staging"),
            Environment(id = UUID.random(), programId = programId, key = "production", name = "Production"),
        )
    }

    @AfterTest
    fun teardown() = bosca.di.ProviderRegistry.clear()

    @Test
    fun `a valid config with a decodable adapter config passes`() = runTest {
        val target = mockk<DeployTarget> {
            every { validateConfig(any()) } returns emptyList()
        }
        provides<DeployTarget>(name = "HELM_VALUES", singleton = true) { target }

        val problems = service.validate(
            repositoryId,
            """
            environments:
              staging:
                target: helm-values
                config: { clusterId: "c", releaseName: "r", namespace: "staging" }
            """.trimIndent(),
        )
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `malformed YAML and missing environments fail with one clear message`() = runTest {
        assertTrue(service.validate(repositoryId, "environments: [not: a: mapping").single().contains("malformed"))
        assertTrue(service.validate(repositoryId, "notEnvironments: {}").single().contains("environments"))
        assertEquals(
            listOf("deploy config declares no environments"),
            service.validate(repositoryId, "environments: {}"),
        )
    }

    @Test
    fun `an unknown target kind is named with the known kinds`() = runTest {
        val problems = service.validate(
            repositoryId,
            """
            environments:
              staging:
                target: helm-valuez
            """.trimIndent(),
        )
        assertTrue(problems.single().contains("helm-valuez"), problems.single())
        assertTrue(problems.single().contains("helm-values"), problems.single())
    }

    @Test
    fun `an environment no linked program declares is rejected naming the known keys`() = runTest {
        val problems = service.validate(
            repositoryId,
            """
            environments:
              prodcution:
                target: helm-values
            """.trimIndent(),
        )
        assertTrue(problems.any { "prodcution" in it && "production" in it }, problems.joinToString())
    }

    @Test
    fun `adapter config problems are surfaced per environment`() = runTest {
        val target = mockk<DeployTarget> {
            every { validateConfig(any()) } returns listOf("google_play config requires packageName")
        }
        provides<DeployTarget>(name = "GOOGLE_PLAY", singleton = true) { target }

        val problems = service.validate(
            repositoryId,
            """
            environments:
              production:
                target: google_play
                artifact: { type: android-aar, namespace: bosca-raw, coordinate: "app:${'$'}{{ version }}" }
                config: { track: "production" }
            """.trimIndent(),
        )
        assertEquals("environment 'production' (google_play): google_play config requires packageName", problems.single())
    }

    @Test
    fun `a targets list with artifact selectors parses and validates per entry`() = runTest {
        val target = mockk<DeployTarget> {
            every { validateConfig(any()) } returns emptyList()
        }
        provides<DeployTarget>(name = "HELM_VALUES", singleton = true) { target }
        provides<DeployTarget>(name = "GOOGLE_PLAY", singleton = true) {
            mockk<DeployTarget> { every { validateConfig(any()) } returns listOf("google_play config requires packageName") }
        }

        val problems = service.validate(
            repositoryId,
            """
            environments:
              production:
                targets:
                  - target: helm-values
                    config: { clusterId: "c", releaseName: "r", namespace: "prod" }
                    artifact: { type: docker, coordinate: "server:${'$'}{{ version }}" }
                  - target: google_play
                    artifact: { type: android-aar, namespace: bosca-raw, coordinate: "app:${'$'}{{ version }}" }
                    config: { track: "production" }
            """.trimIndent(),
        )
        // The helm entry is clean; the Play entry's problem is attributed to its target.
        assertEquals("environment 'production' (google_play): google_play config requires packageName", problems.single())
    }

    @Test
    fun `google play requires selected artifact coordinates at push time`() = runTest {
        provides<DeployTarget>(name = "GOOGLE_PLAY", singleton = true) {
            mockk<DeployTarget> { every { validateConfig(any()) } returns emptyList() }
        }

        val problems = service.validate(
            repositoryId,
            """
            environments:
              production:
                target: google_play
                config: { packageName: "io.example.app", serviceAccountSecret: "play-sa" }
            """.trimIndent(),
        )

        assertTrue(problems.any { "requires an artifact selector" in it }, problems.joinToString())
    }

    @Test
    fun `google play artifact requires a namespace and complete name version coordinate`() = runTest {
        provides<DeployTarget>(name = "GOOGLE_PLAY", singleton = true) {
            mockk<DeployTarget> { every { validateConfig(any()) } returns emptyList() }
        }

        val problems = service.validate(
            repositoryId,
            """
            environments:
              production:
                targets:
                  - target: google_play
                    artifact: { type: android-aar, coordinate: "app" }
                    config: { packageName: "io.example.app", serviceAccountSecret: "play-sa" }
                  - target: google_play
                    artifact: { type: android-aar, namespace: bosca-raw, coordinate: "app:" }
                    config: { packageName: "io.example.app", serviceAccountSecret: "play-sa" }
            """.trimIndent(),
        )

        assertTrue(problems.any { "requires namespace" in it }, problems.joinToString())
        assertEquals(2, problems.count { "must be name:version" in it }, problems.joinToString())
    }

    @Test
    fun `blank artifact coordinates are rejected before adapter validation`() = runTest {
        val problems = service.validate(
            repositoryId,
            """
            environments:
              production:
                target: helm-values
                artifact: { type: docker, coordinate: "" }
            """.trimIndent(),
        )

        assertEquals(
            "environment 'production' (helm-values): artifact selector has a blank coordinate",
            problems.single(),
        )
    }

    @Test
    fun `an unlinked repository skips the environment-key check but still validates structure`() = runTest {
        val unlinked = UUID.random()
        coEvery { projectRepositories.listByRepository(unlinked) } returns emptyList()

        val problems = service.validate(
            unlinked,
            """
            environments:
              anything-goes:
                target: bogus
            """.trimIndent(),
        )
        assertTrue(problems.single().contains("bogus"), problems.joinToString())
    }

    @Test
    fun `links whose projects disappeared do not invent environment validation context`() = runTest {
        val repositoryWithMissingProject = UUID.random()
        val missingProjectId = UUID.random()
        coEvery { projectRepositories.listByRepository(repositoryWithMissingProject) } returns listOf(
            ProjectRepositoryLink(projectId = missingProjectId, repositoryId = repositoryWithMissingProject),
        )
        coEvery { projectService.getById(missingProjectId) } returns null

        val problems = service.validate(
            repositoryWithMissingProject,
            """
            environments:
              custom:
                target: unknown
            """.trimIndent(),
        )

        assertEquals(1, problems.size)
        assertTrue("unknown target" in problems.single())
    }
}

class DeployConfigPushListenerTest {

    private val deployConfigService = mockk<DeployConfigService>()
    private val repositoryWrite = mockk<RepositoryWriteService>()
    private val commitStatusService = mockk<CommitStatusService>(relaxed = true)

    // The validator alone — constructing the listener would start its subscription loop against a
    // mock PubSub, whose immediately-completing flow turns the loop into a JVM-wide busy spin.
    private val listener = DeployConfigPushValidator(
        deployConfigService = deployConfigService,
        repositoryWrite = repositoryWrite,
        commitStatusService = commitStatusService,
    )

    private val repositoryId = UUID.random()
    private val path = DeployConfigService.DEPLOY_CONFIG_PATH

    private fun push(before: String = "a".repeat(40), after: String = "b".repeat(40)) =
        PushEvent(repositoryId = repositoryId, ref = "refs/heads/main", beforeSha = before, afterSha = after)

    @Test
    fun `a push that changes the file records a status from the validation outcome`() = runTest {
        val event = push()
        coEvery { repositoryWrite.readFile(repositoryId, event.afterSha, path) } returns "environments: {}"
        coEvery { repositoryWrite.readFile(repositoryId, event.beforeSha, path) } returns "old"
        coEvery { deployConfigService.validate(repositoryId, "environments: {}") } returns
            listOf("deploy config declares no environments")

        listener.handle(event)

        coVerify {
            commitStatusService.recordStatus(
                repositoryId, event.afterSha, DeployConfigPushValidator.STATUS_CONTEXT,
                CommitStatusState.FAILURE, "deploy config declares no environments", null,
            )
        }
    }

    @Test
    fun `a push that does not change the file stays silent`() = runTest {
        val event = push()
        coEvery { repositoryWrite.readFile(repositoryId, event.afterSha, path) } returns "same"
        coEvery { repositoryWrite.readFile(repositoryId, event.beforeSha, path) } returns "same"

        listener.handle(event)

        coVerify(exactly = 0) { commitStatusService.recordStatus(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a repository without the file stays silent`() = runTest {
        val event = push()
        coEvery { repositoryWrite.readFile(repositoryId, event.afterSha, path) } returns null

        listener.handle(event)

        coVerify(exactly = 0) { commitStatusService.recordStatus(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a branch-creation push validates without a before read`() = runTest {
        val event = push(before = "0".repeat(40))
        coEvery { repositoryWrite.readFile(repositoryId, event.afterSha, path) } returns "environments: {}"
        coEvery { deployConfigService.validate(repositoryId, any()) } returns emptyList()

        listener.handle(event)

        coVerify {
            commitStatusService.recordStatus(
                repositoryId, event.afterSha, DeployConfigPushValidator.STATUS_CONTEXT,
                CommitStatusState.SUCCESS, "deploy.yaml is valid", null,
            )
        }
        coVerify(exactly = 0) { repositoryWrite.readFile(repositoryId, event.beforeSha, path) }
    }
}
