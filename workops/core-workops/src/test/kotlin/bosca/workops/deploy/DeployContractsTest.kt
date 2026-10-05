package bosca.workops.deploy

import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.environment.DeployInput
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.environment.EnvironmentDeploymentStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeployContractsTest {

    @Test
    fun `target kind database mapper translates PostgreSQL casing and binds lowercase labels`() {
        val result = mockk<ResultSet>()
        every { result.getString(1) } returns "google_play"
        every { result.getString("target_kind") } returnsMany listOf("APP_STORE", null)

        assertEquals(
            DeployTargetKind.GOOGLE_PLAY,
            DeployTargetKindMapper.map(DeployTargetKind::class, emptyList(), result, 1),
        )
        assertEquals(
            DeployTargetKind.APP_STORE,
            DeployTargetKindMapper.map(DeployTargetKind::class, emptyList(), result, "target_kind"),
        )
        assertNull(DeployTargetKindMapper.map(DeployTargetKind::class, emptyList(), result, "target_kind"))

        val statement = mockk<PreparedStatement>(relaxed = true)
        assertSame(
            statement,
            DeployTargetKindMapper.bind(DeployTargetKind::class, emptyList(), statement, 2, DeployTargetKind.HELM_VALUES),
        )
        assertSame(statement, DeployTargetKindMapper.bind(DeployTargetKind::class, emptyList(), statement, 3, null))
        verify { statement.setString(2, "helm_values") }
        verify { statement.setNull(3, java.sql.Types.VARCHAR) }
    }

    @Test
    fun `target kinds normalize known names and reject unknown targets`() {
        assertEquals(DeployTargetKind.GOOGLE_PLAY, DeployTargetEntry(target = "google-play").targetKind())
        assertEquals(DeployTargetKind.APP_STORE, DeployTargetEntry(target = "App_Store").targetKind())
        assertNull(DeployTargetEntry(target = "custom-target").targetKind())
    }

    @Test
    fun `deploy request contracts retain durable artifact target and build identity`() {
        val environmentId = UUID.random()
        val projectId = UUID.random()
        val versionId = UUID.random()
        val principalId = UUID.random()
        val publicationId = UUID.random()
        val allocationId = UUID.random()
        val configRepositoryId = UUID.random()
        val config = JsonObject(emptyMap())
        val artifact = DeployArtifact(publicationId, "android-aab", "mobile", "mobile:6.2.0")
        val buildNumber = DeployBuildNumber(allocationId, 42, "42")

        val deploy = DeployRequest(
            environmentId = environmentId,
            projectId = projectId,
            versionId = versionId,
            version = "6.2.0",
            config = config,
            deployedByPrincipalId = principalId,
            artifact = artifact,
            buildNumber = buildNumber,
            configRepositoryId = configRepositoryId,
        )
        val rollback = RollbackRequest(
            environmentId = environmentId,
            projectId = projectId,
            toRevision = 2,
            config = config,
            deployedByPrincipalId = principalId,
            artifact = artifact,
            buildNumber = buildNumber,
            configRepositoryId = configRepositoryId,
        )
        val rollout = RolloutRequest(
            environmentId = environmentId,
            projectId = projectId,
            rolloutPercentage = 50.5,
            config = config,
            deployedByPrincipalId = principalId,
            artifact = artifact,
            buildNumber = buildNumber,
            configRepositoryId = configRepositoryId,
        )

        assertEquals(publicationId, artifact.publicationId)
        assertEquals("android-aab", artifact.type)
        assertEquals("mobile", artifact.namespace)
        assertEquals("mobile:6.2.0", artifact.coordinate)
        assertEquals(allocationId, buildNumber.allocationId)
        assertEquals(42, buildNumber.number)
        assertEquals("42", buildNumber.value)
        assertSame(artifact, deploy.artifact)
        assertSame(buildNumber, deploy.buildNumber)
        assertEquals(configRepositoryId, deploy.configRepositoryId)
        assertSame(artifact, rollback.artifact)
        assertSame(buildNumber, rollback.buildNumber)
        assertEquals(configRepositoryId, rollback.configRepositoryId)
        assertEquals(50.5, rollout.rolloutPercentage)
        assertSame(artifact, rollout.artifact)
        assertSame(buildNumber, rollout.buildNumber)
        assertEquals(configRepositoryId, rollout.configRepositoryId)
    }

    @Test
    fun `deploy request contracts preserve safe defaults`() {
        val deploy = DeployRequest(
            environmentId = UUID.random(),
            projectId = UUID.random(),
            versionId = UUID.random(),
            version = "6.2.0",
            config = JsonObject(emptyMap()),
            deployedByPrincipalId = UUID.random(),
        )
        val rollback = RollbackRequest(
            environmentId = deploy.environmentId,
            projectId = deploy.projectId,
            toRevision = 0,
            config = deploy.config,
            deployedByPrincipalId = deploy.deployedByPrincipalId,
        )
        val rollout = RolloutRequest(
            environmentId = deploy.environmentId,
            projectId = deploy.projectId,
            rolloutPercentage = 0.0,
            config = deploy.config,
            deployedByPrincipalId = deploy.deployedByPrincipalId,
        )

        listOf(deploy.artifact, deploy.buildNumber, rollback.artifact, rollback.buildNumber, rollout.artifact, rollout.buildNumber)
            .forEach(::assertNull)
        assertNull(deploy.configRepositoryId)
        assertNull(rollback.configRepositoryId)
        assertNull(rollout.configRepositoryId)
    }

    @Test
    fun `targets without staged delivery fail rollout closed`() {
        val target = object : DeployTarget {
            override val kind = DeployTargetKind.APP_STORE
            override suspend fun deploy(request: DeployRequest, authentication: AuthenticationContext): DeployOutcome =
                error("unused")

            override suspend fun rollback(request: RollbackRequest, authentication: AuthenticationContext): DeployOutcome =
                error("unused")
        }
        val request = RolloutRequest(
            environmentId = UUID.random(),
            projectId = UUID.random(),
            rolloutPercentage = 25.0,
            config = JsonObject(emptyMap()),
            deployedByPrincipalId = UUID.random(),
        )

        val failure = assertFailsWith<IllegalStateException> {
            runBlocking { target.rollout(request, mockk()) }
        }

        assertTrue("APP_STORE" in (failure.message ?: ""))
        assertEquals(emptyList(), target.validateConfig(JsonObject(emptyMap())))
    }

    @Test
    fun `environment deployment contracts retain independent target and build allocation`() {
        val environmentId = UUID.random()
        val projectId = UUID.random()
        val versionId = UUID.random()
        val allocationId = UUID.random()
        val deployment = EnvironmentDeployment(
            environmentId = environmentId,
            projectId = projectId,
            targetKind = DeployTargetKind.APP_STORE,
            versionId = versionId,
            appBuildNumberAllocationId = allocationId,
            status = EnvironmentDeploymentStatus.DEPLOYED,
        )
        val input = DeployInput(
            environmentId = environmentId,
            projectId = projectId,
            targetKind = DeployTargetKind.APP_STORE,
            versionId = versionId,
            appBuildNumberAllocationId = allocationId,
        )
        val defaultDeployment = EnvironmentDeployment(
            environmentId = environmentId,
            projectId = projectId,
            versionId = versionId,
        )
        val defaultInput = DeployInput(environmentId, projectId, versionId = versionId)

        assertEquals(DeployTargetKind.APP_STORE, deployment.targetKind)
        assertEquals(allocationId, deployment.appBuildNumberAllocationId)
        assertEquals(DeployTargetKind.APP_STORE, input.targetKind)
        assertEquals(allocationId, input.appBuildNumberAllocationId)
        assertEquals(DeployTargetKind.HELM, defaultDeployment.targetKind)
        assertNull(defaultDeployment.appBuildNumberAllocationId)
        assertEquals(DeployTargetKind.HELM, defaultInput.targetKind)
        assertNull(defaultInput.appBuildNumberAllocationId)
    }
}
