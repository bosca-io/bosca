package bosca.git.service

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ReleaseBuildNumberContractTest {

    @Test
    fun `request defaults to the stable build key and no migration floor`() {
        val repositoryId = UUID.random()
        val pipelineRunId = UUID.random()
        val request = ReleaseBuildNumberRequest(
            repositoryId = repositoryId,
            pipelineRunId = pipelineRunId,
            sourceCommitSha = "a".repeat(40),
            sourceVersion = "6.2.0",
            platform = "android",
            applicationId = "io.bosca.app",
        )

        assertEquals(repositoryId, request.repositoryId)
        assertEquals(pipelineRunId, request.pipelineRunId)
        assertEquals("a".repeat(40), request.sourceCommitSha)
        assertEquals("6.2.0", request.sourceVersion)
        assertEquals("android", request.platform)
        assertEquals("io.bosca.app", request.applicationId)
        assertEquals("default", request.buildKey)
        assertEquals(null, request.minimum)
    }

    @Test
    fun `request and outcome retain explicit store values`() {
        val request = ReleaseBuildNumberRequest(
            repositoryId = UUID.random(),
            pipelineRunId = UUID.random(),
            sourceCommitSha = "b".repeat(64),
            sourceVersion = "6.2.0",
            platform = "ios",
            applicationId = "com.example.app",
            buildKey = "release",
            minimum = "2.0.0",
        )
        val outcome = ReleaseBuildNumberOutcome(number = 10_001, value = "2.0.0", reused = false)

        assertEquals("release", request.buildKey)
        assertEquals("2.0.0", request.minimum)
        assertEquals(10_001, outcome.number)
        assertEquals("2.0.0", outcome.value)
        assertFalse(outcome.reused)
    }

    @Test
    fun `play rollout request retains the exact target percentage and initiating principal`() {
        val repositoryId = UUID.random()
        val principalId = UUID.random()
        val request = ReleasePlayRolloutRequest(
            targetRepositoryId = repositoryId,
            ref = "refs/tags/6.2.0",
            environmentKey = "production",
            target = "play",
            rolloutPercentage = 37.5,
            parameters = mapOf("release.version" to "6.2.0"),
            initiatorPrincipalId = principalId,
        )

        assertEquals(repositoryId, request.targetRepositoryId)
        assertEquals("refs/tags/6.2.0", request.ref)
        assertEquals("production", request.environmentKey)
        assertEquals("play", request.target)
        assertEquals(37.5, request.rolloutPercentage)
        assertEquals(mapOf("release.version" to "6.2.0"), request.parameters)
        assertEquals(principalId, request.initiatorPrincipalId)
    }

    @Test
    fun `rollback request retains the target revision overrides and initiating principal`() {
        val repositoryId = UUID.random()
        val principalId = UUID.random()
        val request = ReleaseRollbackRequest(
            targetRepositoryId = repositoryId,
            ref = "refs/tags/6.2.0",
            environmentKey = "production",
            target = "helm",
            toRevision = 4,
            overrides = mapOf("resetValues" to "true"),
            parameters = mapOf("release.version" to "6.2.0"),
            initiatorPrincipalId = principalId,
        )

        assertEquals(repositoryId, request.targetRepositoryId)
        assertEquals("refs/tags/6.2.0", request.ref)
        assertEquals("production", request.environmentKey)
        assertEquals("helm", request.target)
        assertEquals(4, request.toRevision)
        assertEquals(mapOf("resetValues" to "true"), request.overrides)
        assertEquals(mapOf("release.version" to "6.2.0"), request.parameters)
        assertEquals(principalId, request.initiatorPrincipalId)
    }
}
