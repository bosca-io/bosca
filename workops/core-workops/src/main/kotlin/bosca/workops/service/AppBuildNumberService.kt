package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.artifact.AllocateAppBuildNumberInput
import bosca.workops.model.artifact.AppBuildNumberAllocation
import bosca.workops.model.artifact.AppBuildNumberAllocationResult
import bosca.workops.model.artifact.AppBuildPlatform

/** Durable, concurrency-safe allocation of mobile-store build identities. */
interface AppBuildNumberService : Service {

    /** Allocates once for a source identity; subsequent calls for that identity return the same row. */
    suspend fun allocate(input: AllocateAppBuildNumberInput): AppBuildNumberAllocationResult

    /** Finds the exact allocation that produced a version from a resolved source commit. */
    suspend fun find(
        repositoryId: UUID,
        sourceCommitSha: String,
        sourceVersion: String,
        platform: AppBuildPlatform,
        applicationId: String,
        buildKey: String = "default",
    ): AppBuildNumberAllocation?
}
