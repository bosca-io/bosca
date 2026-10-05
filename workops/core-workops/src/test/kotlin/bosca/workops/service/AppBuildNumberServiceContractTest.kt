package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.artifact.AllocateAppBuildNumberInput
import bosca.workops.model.artifact.AppBuildNumberAllocation
import bosca.workops.model.artifact.AppBuildNumberAllocationResult
import bosca.workops.model.artifact.AppBuildPlatform
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class AppBuildNumberServiceContractTest {

    @Test
    fun `service contract preserves allocation and applies the default build key on lookup`() = runBlocking {
        val repositoryId = UUID.random()
        val input = AllocateAppBuildNumberInput(
            platform = AppBuildPlatform.ANDROID,
            applicationId = "io.bosca.app",
            repositoryId = repositoryId,
            sourceCommitSha = "a".repeat(40),
            sourceVersion = "6.2.0",
            pipelineRunId = UUID.random(),
        )
        val allocation = AppBuildNumberAllocation(
            platform = input.platform,
            applicationId = input.applicationId,
            repositoryId = repositoryId,
            sourceCommitSha = input.sourceCommitSha,
            sourceVersion = input.sourceVersion,
            pipelineRunId = input.pipelineRunId,
            number = 42,
            value = "42",
        )
        val service = RecordingAppBuildNumberService(allocation)

        val result = service.allocate(input)
        val found = service.find(
            repositoryId,
            input.sourceCommitSha,
            input.sourceVersion,
            input.platform,
            input.applicationId,
        )

        assertSame(allocation, result.allocation)
        assertFalse(result.reused)
        assertSame(allocation, found)
        assertEquals("default", service.requestedBuildKey)
    }

    private class RecordingAppBuildNumberService(
        private val allocation: AppBuildNumberAllocation,
    ) : AppBuildNumberService {
        lateinit var requestedBuildKey: String

        override suspend fun allocate(input: AllocateAppBuildNumberInput) =
            AppBuildNumberAllocationResult(allocation, reused = false)

        override suspend fun find(
            repositoryId: UUID,
            sourceCommitSha: String,
            sourceVersion: String,
            platform: AppBuildPlatform,
            applicationId: String,
            buildKey: String,
        ): AppBuildNumberAllocation {
            requestedBuildKey = buildKey
            return allocation
        }
    }
}
