package bosca.workops.repository

import bosca.db.annotation.ColumnName
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.artifact.AppBuildNumberAllocation
import bosca.workops.model.artifact.AppBuildPlatform

data class AppBuildNumberCounter(
    val platform: AppBuildPlatform,
    @ColumnName("application_id")
    val applicationId: String,
    @ColumnName("version_scope")
    val versionScope: String,
    @ColumnName("last_number")
    val lastNumber: Long,
    @ColumnName("modified_at")
    val modifiedAt: OffsetDateTime,
    val version: Long,
)

@Repository
interface AppBuildNumberRepository {

    @Query(
        """
        insert into workops.app_build_number_counter(platform, application_id, version_scope, last_number)
        values (:platform, :applicationId, :versionScope, 0)
        on conflict (platform, application_id, version_scope) do nothing
        returning *
        """
    )
    suspend fun ensureCounter(platform: String, applicationId: String, versionScope: String): AppBuildNumberCounter?

    /** Serializes every allocation for one store application/version scope inside the caller's transaction. */
    @Query(
        """
        select * from workops.app_build_number_counter
        where platform = :platform and application_id = :applicationId and version_scope = :versionScope
        for update
        """
    )
    suspend fun lockCounter(platform: String, applicationId: String, versionScope: String): AppBuildNumberCounter?

    @Query(
        """
        update workops.app_build_number_counter
        set last_number = :number, modified_at = now(), version = version + 1
        where platform = :platform and application_id = :applicationId
          and version_scope = :versionScope and version = :expectedVersion
        returning *
        """
    )
    suspend fun updateCounter(
        platform: String,
        applicationId: String,
        versionScope: String,
        number: Long,
        expectedVersion: Long,
    ): AppBuildNumberCounter?

    @Query(
        """
        select * from workops.app_build_number_allocation
        where repository_id = :repositoryId
          and source_commit_sha = :sourceCommitSha
          and source_version = :sourceVersion
          and platform = :platform
          and application_id = :applicationId
          and build_key = :buildKey
        """
    )
    suspend fun find(
        repositoryId: UUID,
        sourceCommitSha: String,
        sourceVersion: String,
        platform: String,
        applicationId: String,
        buildKey: String,
    ): AppBuildNumberAllocation?

    @Query(
        """
        insert into workops.app_build_number_allocation
            (platform, application_id, version_scope, build_key, repository_id, source_commit_sha,
             source_version, pipeline_run_id, number, value)
        values
            (:platform, :applicationId, :versionScope, :buildKey, :repositoryId, :sourceCommitSha,
             :sourceVersion, :pipelineRunId, :number, :value)
        returning *
        """
    )
    suspend fun add(
        platform: String,
        applicationId: String,
        versionScope: String,
        buildKey: String,
        repositoryId: UUID,
        sourceCommitSha: String,
        sourceVersion: String,
        pipelineRunId: UUID,
        number: Long,
        value: String,
    ): AppBuildNumberAllocation
}
