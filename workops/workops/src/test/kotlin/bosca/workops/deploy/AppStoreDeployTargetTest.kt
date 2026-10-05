package bosca.workops.deploy

import bosca.pipelines.service.PipelineSecretService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.store.pipelines.AppStorePublisher
import bosca.store.pipelines.AppStoreSubmitResult
import bosca.workops.model.environment.DeployInput
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.environment.EnvironmentDeploymentStatus
import bosca.workops.service.EnvironmentService
import bosca.workops.service.ReleaseNotesService
import bosca.workops.model.release.LocalizedReleaseNotes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppStoreDeployTargetTest {

    private val environments = mockk<EnvironmentService>()
    private val secrets = mockk<PipelineSecretService>()
    private val publisher = mockk<AppStorePublisher>()
    private val releaseNotes = mockk<ReleaseNotesService>()
    private val authentication = mockk<AuthenticationContext>(relaxed = true)
    private val principalId = UUID.random()
    private val environmentId = UUID.random()
    private val projectId = UUID.random()
    private val versionId = UUID.random()
    private val deploymentId = UUID.random()
    private val allocationId = UUID.random()
    private val deploymentInput = slot<DeployInput>()

    private val pending = EnvironmentDeployment(
        id = deploymentId,
        environmentId = environmentId,
        projectId = projectId,
        targetKind = DeployTargetKind.APP_STORE,
        versionId = versionId,
    )
    private val target = AppStoreDeployTarget(environments, secrets, publisher, releaseNotes)

    private fun config(
        groups: String = "[\"Beta\",\"Employees\"]",
        phasedRelease: Boolean = false,
        legacyGroup: String? = null,
    ): JsonElement = Json.parseToJsonElement(
        if (legacyGroup != null) {
            """{"bundleId":"io.example.app","testflightGroup":"$legacyGroup","ascKeySecret":"asc-key"}"""
        } else {
            """{"bundleId":"io.example.app","testflightGroups":$groups,"phasedRelease":$phasedRelease,"ascKeySecret":"asc-key"}"""
        },
    )

    private fun request(config: JsonElement = config()) = DeployRequest(
        environmentId = environmentId,
        projectId = projectId,
        versionId = versionId,
        version = "1.4.0",
        config = config,
        deployedByPrincipalId = principalId,
        buildNumber = DeployBuildNumber(allocationId, 42, "42"),
    )

    @BeforeTest
    fun setup() {
        coEvery { releaseNotes.requireLocalized(versionId, listOf("en-US")) } returns listOf(
            LocalizedReleaseNotes("en-US", "Play notes", "App Store notes", "TestFlight notes"),
        )
        coEvery { environments.createDeployment(capture(deploymentInput), principalId) } returns pending
        coEvery { environments.markDeployed(deploymentId, principalId, any()) } returns
            pending.copy(status = EnvironmentDeploymentStatus.DEPLOYED)
        coEvery { environments.markFailed(deploymentId, any()) } returns
            pending.copy(status = EnvironmentDeploymentStatus.FAILED)
        coEvery { environments.markRolledBack(deploymentId, any()) } returns
            pending.copy(status = EnvironmentDeploymentStatus.ROLLED_BACK)
        coEvery { secrets.resolve("asc-key") } returns "asc-credential-json"
        coEvery {
            publisher.assignBetaGroupsWithReleaseNotes(
                "asc-credential-json", "io.example.app", "1.4.0", "42", listOf("Beta", "Employees"),
                mapOf("en-US" to "TestFlight notes"),
            )
        } returns AppStoreSubmitResult("1.4.0", "42", "ASSIGNED")
        coEvery {
            publisher.assignBetaGroupsWithReleaseNotes(any(), any(), any(), any(), listOf("Legacy"), any())
        } returns AppStoreSubmitResult("1.4.0", "42", "ASSIGNED")
        coEvery {
            publisher.submitForReviewWithReleaseNotes(
                "asc-credential-json", "io.example.app", "1.4.0", "42", true,
                mapOf("en-US" to "App Store notes"),
            )
        } returns AppStoreSubmitResult("1.4.0", "42", "WAITING_FOR_REVIEW")
        coEvery {
            publisher.removeBetaGroups(
                "asc-credential-json", "io.example.app", "1.4.0", "42", listOf("Beta", "Employees"),
            )
        } returns AppStoreSubmitResult("1.4.0", "42", "REMOVED")
        coEvery {
            publisher.haltPhasedRelease("asc-credential-json", "io.example.app", "1.4.0")
        } returns AppStoreSubmitResult("1.4.0", "", "PAUSED")
    }

    @Test
    fun `deploy assigns the durable build to every configured TestFlight group`() = runTest {
        val outcome = target.deploy(request(), authentication)

        assertEquals(EnvironmentDeploymentStatus.DEPLOYED, outcome.status)
        assertEquals(allocationId, deploymentInput.captured.appBuildNumberAllocationId)
        assertTrue("ASSIGNED" in outcome.reference && "1.4.0" in outcome.reference)
        coVerify(exactly = 1) {
            publisher.assignBetaGroupsWithReleaseNotes(
                "asc-credential-json", "io.example.app", "1.4.0", "42", listOf("Beta", "Employees"),
                mapOf("en-US" to "TestFlight notes"),
            )
        }
        coVerify(exactly = 0) { publisher.submitForReviewWithReleaseNotes(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `deploy can combine TestFlight assignment and phased App Store review`() = runTest {
        val outcome = target.deploy(request(config(phasedRelease = true)), authentication)

        assertTrue("ASSIGNED + WAITING_FOR_REVIEW" in outcome.reference)
        coVerify { publisher.assignBetaGroupsWithReleaseNotes(any(), any(), any(), any(), any(), any()) }
        coVerify {
            publisher.submitForReviewWithReleaseNotes(
                "asc-credential-json", "io.example.app", "1.4.0", "42", true, any(),
            )
        }
    }

    @Test
    fun `legacy singular TestFlight group remains readable`() = runTest {
        val outcome = target.deploy(request(config(legacyGroup = "Legacy")), authentication)

        assertEquals(EnvironmentDeploymentStatus.DEPLOYED, outcome.status)
        coVerify { publisher.assignBetaGroupsWithReleaseNotes(any(), any(), any(), any(), listOf("Legacy"), any()) }
    }

    @Test
    fun `publisher failure marks deployment failed while cancellation is propagated unchanged`() = runTest {
        coEvery { publisher.assignBetaGroupsWithReleaseNotes(any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("build not processed")
        assertFailsWith<IllegalStateException> { target.deploy(request(), authentication) }
        coVerify(exactly = 1) { environments.markFailed(deploymentId, any()) }

        coEvery { publisher.assignBetaGroupsWithReleaseNotes(any(), any(), any(), any(), any(), any()) } throws
            CancellationException("cancelled")
        assertFailsWith<CancellationException> { target.deploy(request(), authentication) }
        coVerify(exactly = 1) { environments.markFailed(deploymentId, any()) }
    }

    @Test
    fun `rollback removes TestFlight groups and pauses phased release for the selected version`() = runTest {
        coEvery { environments.currentState(environmentId) } returns listOf(pending)

        val outcome = target.rollback(
            RollbackRequest(
                environmentId = environmentId,
                projectId = projectId,
                toRevision = 1,
                config = config(phasedRelease = true),
                deployedByPrincipalId = principalId,
                version = "1.4.0",
                buildNumber = DeployBuildNumber(allocationId, 42, "42"),
            ),
            authentication,
        )

        assertEquals(EnvironmentDeploymentStatus.ROLLED_BACK, outcome.status)
        assertTrue("groups removed" in outcome.reference && "phased release paused" in outcome.reference)
        coVerify {
            publisher.removeBetaGroups(
                "asc-credential-json", "io.example.app", "1.4.0", "42", listOf("Beta", "Employees"),
            )
        }
        coVerify { publisher.haltPhasedRelease("asc-credential-json", "io.example.app", "1.4.0") }
        coVerify { environments.markRolledBack(deploymentId, pending.version) }
    }

    @Test
    fun `config validation reports decoding identity build key credential group and action errors`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString(AppStoreTargetConfig.serializer(), "{}")
        }
        assertTrue(target.validateConfig(config()).isEmpty())
        assertTrue(
            target.validateConfig(
                Json.parseToJsonElement(
                    """{"bundleId":"io.example.app","phasedRelease":true,"ascKeySecret":"asc-key"}""",
                ),
            ).isEmpty(),
        )
        assertTrue(target.validateConfig(Json.parseToJsonElement("[]")).single().contains("does not decode"))

        val problems = target.validateConfig(
            Json.parseToJsonElement(
                """{"bundleId":" ","buildNumberKey":"","testflightGroups":[" "],"ascKeySecret":" "}""",
            ),
        )
        assertTrue(problems.any { "bundleId" in it })
        assertTrue(problems.any { "buildNumberKey" in it })
        assertTrue(problems.any { "ascKeySecret" in it })
        assertTrue(problems.any { "blank names" in it })

        val localeProblems = target.validateConfig(
            Json.parseToJsonElement(
                """{"bundleId":"io.example.app","testflightGroups":["Beta"],"ascKeySecret":"asc-key","releaseNotesLocales":[]}""",
            ),
        )
        assertTrue(localeProblems.any { "releaseNotesLocales" in it })
        assertTrue(
            target.validateConfig(
                Json.parseToJsonElement(
                    """{"bundleId":"io.example.app","testflightGroups":["Beta"],"ascKeySecret":"asc-key","releaseNotesLocales":[" "]}""",
                ),
            ).any { "blanks" in it },
        )

        val noAction = target.validateConfig(
            Json.parseToJsonElement("""{"bundleId":"io.example.app","ascKeySecret":"asc-key"}"""),
        )
        assertTrue(noAction.any { "testflightGroups" in it })
    }

    @Test
    fun `deploy validates durable build config and credential before publisher invocation`() = runTest {
        assertFailsWith<IllegalStateException> { target.deploy(request().copy(buildNumber = null), authentication) }
        assertFailsWith<IllegalArgumentException> {
            target.deploy(
                request(Json.parseToJsonElement("""{"bundleId":" ","testflightGroups":["Beta"],"ascKeySecret":"asc-key"}""")),
                authentication,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            target.deploy(
                request(Json.parseToJsonElement("""{"bundleId":"io.example.app","ascKeySecret":"asc-key"}""")),
                authentication,
            )
        }
        for (invalid in listOf(
            """{"bundleId":"io.example.app","testflightGroups":[" "],"ascKeySecret":"asc-key"}""",
            """{"bundleId":"io.example.app","testflightGroups":["Beta"],"ascKeySecret":"asc-key","releaseNotesLocales":[]}""",
            """{"bundleId":"io.example.app","testflightGroups":["Beta"],"ascKeySecret":"asc-key","releaseNotesLocales":[" "]}""",
        )) {
            assertFailsWith<IllegalArgumentException> {
                target.deploy(request(Json.parseToJsonElement(invalid)), authentication)
            }
        }
        coEvery { secrets.resolve("asc-key") } returns null
        val failure = assertFailsWith<IllegalStateException> { target.deploy(request(), authentication) }
        assertTrue("asc-key" in failure.message.orEmpty())
        coVerify(exactly = 1) { environments.markFailed(deploymentId, any()) }
    }

    @Test
    fun `rollback requires durable build and exact current App Store deployment`() = runTest {
        val rollback = RollbackRequest(
            environmentId = environmentId,
            projectId = projectId,
            toRevision = 1,
            config = config(),
            deployedByPrincipalId = principalId,
            version = "1.4.0",
            buildNumber = DeployBuildNumber(allocationId, 42, "42"),
        )
        coEvery { environments.currentState(environmentId) } returns listOf(
            pending.copy(projectId = UUID.random()),
            pending.copy(targetKind = DeployTargetKind.GOOGLE_PLAY),
        )

        assertTrue("no current" in assertFailsWith<IllegalStateException> {
            target.rollback(rollback, authentication)
        }.message.orEmpty())
        assertFailsWith<IllegalStateException> {
            target.rollback(rollback.copy(buildNumber = null), authentication)
        }
    }

    @Test
    fun `rollback supports group-only and phased-only App Store actions`() = runTest {
        coEvery { environments.currentState(environmentId) } returns listOf(pending)
        val base = RollbackRequest(
            environmentId = environmentId,
            projectId = projectId,
            toRevision = 1,
            config = config(),
            deployedByPrincipalId = principalId,
            version = "1.4.0",
            buildNumber = DeployBuildNumber(allocationId, 42, "42"),
        )

        val groupOnly = target.rollback(base, authentication)
        val phasedOnlyConfig = Json.parseToJsonElement(
            """{"bundleId":"io.example.app","testflightGroups":[],"phasedRelease":true,"ascKeySecret":"asc-key"}""",
        )
        val phasedOnly = target.rollback(base.copy(config = phasedOnlyConfig), authentication)

        assertTrue("groups removed" in groupOnly.reference)
        assertTrue("phased release paused" !in groupOnly.reference)
        assertTrue("groups removed" !in phasedOnly.reference)
        assertTrue("phased release paused" in phasedOnly.reference)
        coVerify(exactly = 1) { publisher.removeBetaGroups(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { publisher.haltPhasedRelease(any(), any(), any()) }
    }
}
