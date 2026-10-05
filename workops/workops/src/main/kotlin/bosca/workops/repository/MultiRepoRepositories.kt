package bosca.workops.repository

import bosca.db.annotation.Repository
import bosca.db.annotation.Query
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.artifact.ApiSurfaceReport
import bosca.workops.model.artifact.ArtifactPublication
import bosca.workops.model.compatibility.CompatibilityTestResult
import bosca.workops.model.dependency.DependencyDeclaration
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.environment.EnvironmentType
import bosca.workops.model.environment.EnvironmentPromotionSource
import bosca.workops.model.environment.EnvironmentTargetType
import bosca.workops.model.pipeline.PipelineRun
import bosca.workops.model.pipeline.PipelineStageRun
import bosca.workops.model.release.ReleaseNotes
import kotlinx.serialization.json.JsonElement

// ── Dependency Declaration ────────────────────────────────────────────

@Repository
interface DependencyDeclarationRepository {

    @Query("select * from workops.dependency_declaration where id = :id")
    suspend fun getById(id: UUID): DependencyDeclaration?

    @Query("select * from workops.dependency_declaration where consumer_project_id = :projectId order by provider_project_id")
    suspend fun listByConsumer(projectId: UUID): List<DependencyDeclaration>

    @Query("select * from workops.dependency_declaration where provider_project_id = :projectId order by consumer_project_id")
    suspend fun listByProvider(projectId: UUID): List<DependencyDeclaration>

    @Query("select * from workops.dependency_declaration where status != 'CURRENT' order by consumer_project_id")
    suspend fun listNonCurrent(): List<DependencyDeclaration>

    @Query(
        """
        insert into workops.dependency_declaration
            (consumer_project_id, consumer_version_id, provider_project_id,
             provider_version_constraint, resolved_provider_version_id,
             dependency_type, artifact_coordinates, status)
        values
            (:consumerProjectId, :consumerVersionId, :providerProjectId,
             :providerVersionConstraint, :resolvedProviderVersionId,
             :dependencyType, :artifactCoordinates, :status)
        returning *
        """
    )
    suspend fun add(
        consumerProjectId: UUID,
        consumerVersionId: UUID?,
        providerProjectId: UUID,
        providerVersionConstraint: String,
        resolvedProviderVersionId: UUID?,
        dependencyType: String,
        artifactCoordinates: String?,
        status: String,
    ): DependencyDeclaration

    @Query(
        """
        update workops.dependency_declaration
        set status = :status, version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun updateStatus(id: UUID, status: String, expectedVersion: Long): DependencyDeclaration?

    @Query(
        """
        update workops.dependency_declaration
        set resolved_provider_version_id = :versionId,
            status = :status,
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun updateResolvedVersion(id: UUID, versionId: UUID?, status: String, expectedVersion: Long): DependencyDeclaration?

    @Query("delete from workops.dependency_declaration where id = :id")
    suspend fun delete(id: UUID)
}

// ── Artifact Publication ──────────────────────────────────────────────

@Repository
interface ArtifactPublicationRepository {

    @Query("select * from workops.artifact_publication where id = :id")
    suspend fun getById(id: UUID): ArtifactPublication?

    @Query("select * from workops.artifact_publication where version_id = :versionId order by coordinates")
    suspend fun listByVersion(versionId: UUID): List<ArtifactPublication>

    @Query("select * from workops.artifact_publication where project_id = :projectId order by published_at desc")
    suspend fun listByProject(projectId: UUID): List<ArtifactPublication>

    @Query("select * from workops.artifact_publication where coordinates = :coordinates")
    suspend fun getByCoordinates(coordinates: String): ArtifactPublication?

    @Query(
        """
        insert into workops.artifact_publication
            (version_id, project_id, artifact_type, coordinates,
             repository_url, checksum_sha256, external_url, namespace, environments, status)
        values
            (:versionId, :projectId, :artifactType, :coordinates,
             :repositoryUrl, :checksumSha256, :externalUrl, :namespace, :environments, 'PENDING')
        on conflict (coordinates) do nothing
        returning *
        """
    )
    suspend fun add(publication: ArtifactPublication): ArtifactPublication?

    @Query(
        """
        update workops.artifact_publication
        set status = :status, published_at = :publishedAt,
            published_by_principal_id = :publishedByPrincipalId,
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun updateStatus(
        id: UUID,
        status: String,
        publishedAt: OffsetDateTime?,
        publishedByPrincipalId: UUID?,
        expectedVersion: Long,
    ): ArtifactPublication?

    @Query("delete from workops.artifact_publication where id = :id")
    suspend fun delete(id: UUID)
}

// ── API Surface Report ────────────────────────────────────────────────

@Repository
interface ApiSurfaceReportRepository {

    @Query("select * from workops.api_surface_report where id = :id")
    suspend fun getById(id: UUID): ApiSurfaceReport?

    @Query("select * from workops.api_surface_report where version_id = :versionId order by analyzed_at desc")
    suspend fun listByVersion(versionId: UUID): List<ApiSurfaceReport>

    @Query("select * from workops.api_surface_report where project_id = :projectId order by analyzed_at desc")
    suspend fun listByProject(projectId: UUID): List<ApiSurfaceReport>

    @Query(
        """
        insert into workops.api_surface_report
            (project_id, version_id, previous_version_id, artifact_publication_id,
             breaking_change_level, changes, analyzer_tool, report_url)
        values
            (:projectId, :versionId, :previousVersionId, :artifactPublicationId,
             :breakingChangeLevel, cast(:changes as jsonb), :analyzerTool, :reportUrl)
        returning *
        """
    )
    suspend fun add(
        projectId: UUID,
        versionId: UUID,
        previousVersionId: UUID,
        artifactPublicationId: UUID?,
        breakingChangeLevel: String,
        changes: String,
        analyzerTool: String,
        reportUrl: String?,
    ): ApiSurfaceReport
}

// ── Pipeline Run ──────────────────────────────────────────────────────

@Repository
interface PipelineRunRepository {

    @Query("select * from workops.pipeline_run where id = :id")
    suspend fun getById(id: UUID): PipelineRun?

    @Query("select * from workops.pipeline_run where project_id = :projectId order by started_at desc")
    suspend fun listByProject(projectId: UUID): List<PipelineRun>

    @Query("select * from workops.pipeline_run where version_id = :versionId order by started_at desc")
    suspend fun listByVersion(versionId: UUID): List<PipelineRun>

    @Query(
        """
        insert into workops.pipeline_run
            (project_id, version_id, pipeline_id, pipeline_name,
             trigger_type, trigger_ref, external_url)
        values
            (:projectId, :versionId, :pipelineId, :pipelineName,
             :triggerType, :triggerRef, :externalUrl)
        returning *
        """
    )
    suspend fun add(
        projectId: UUID,
        versionId: UUID?,
        pipelineId: String,
        pipelineName: String,
        triggerType: String,
        triggerRef: String?,
        externalUrl: String?,
    ): PipelineRun

    @Query(
        """
        update workops.pipeline_run
        set status = :status, completed_at = :completedAt, version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun updateStatus(id: UUID, status: String, completedAt: OffsetDateTime?, expectedVersion: Long): PipelineRun?
}

@Repository
interface PipelineStageRunRepository {

    @Query("select * from workops.pipeline_stage_run where id = :id")
    suspend fun getById(id: UUID): PipelineStageRun?

    @Query("select * from workops.pipeline_stage_run where pipeline_run_id = :pipelineRunId order by started_at")
    suspend fun listByPipelineRun(pipelineRunId: UUID): List<PipelineStageRun>

    @Query(
        """
        insert into workops.pipeline_stage_run
            (pipeline_run_id, stage_name, status, started_at, external_url)
        values
            (:pipelineRunId, :stageName, :status, :startedAt, :externalUrl)
        returning *
        """
    )
    suspend fun add(
        pipelineRunId: UUID,
        stageName: String,
        status: String,
        startedAt: OffsetDateTime?,
        externalUrl: String?,
    ): PipelineStageRun

    @Query(
        """
        update workops.pipeline_stage_run
        set status = :status, completed_at = :completedAt
        where id = :id
        returning *
        """
    )
    suspend fun updateStatus(id: UUID, status: String, completedAt: OffsetDateTime?): PipelineStageRun?
}

// ── Environment Type ──────────────────────────────────────────────────

@Repository
interface EnvironmentTypeRepository {

    @Query("select * from workops.environment_type order by display_order, name")
    suspend fun list(): List<EnvironmentType>

    @Query("select * from workops.environment_type where id = :id")
    suspend fun getById(id: UUID): EnvironmentType?

    @Query("select * from workops.environment_type where name = :name")
    suspend fun getByName(name: String): EnvironmentType?

    @Query(
        """
        insert into workops.environment_type (name, description, display_order)
        values (:name, :description, :displayOrder)
        returning *
        """
    )
    suspend fun add(name: String, description: String?, displayOrder: Int): EnvironmentType

    @Query(
        """
        update workops.environment_type
        set name = :name, description = :description, display_order = :displayOrder,
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun update(id: UUID, name: String, description: String?, displayOrder: Int, expectedVersion: Long): EnvironmentType?

    /** How many environments (across all programs) instantiate this type — guards removal. */
    @Query("select count(*) from workops.environment where type_id = :typeId")
    suspend fun usageCount(typeId: UUID): Long

    @Query("delete from workops.environment_type where id = :id")
    suspend fun delete(id: UUID)
}

// ── Environment ───────────────────────────────────────────────────────

@Repository
interface EnvironmentRepository {

    @Query("select * from workops.environment where id = :id")
    suspend fun getById(id: UUID): Environment?

    @Query("select * from workops.environment where program_id = :programId order by display_order")
    suspend fun listByProgram(programId: UUID): List<Environment>

    @Query("select * from workops.environment where program_id = :programId and key = :key")
    suspend fun getByProgramAndKey(programId: UUID, key: String): Environment?

    @Query(
        """
        insert into workops.environment
            (program_id, key, name, description, display_order, requires_approval, auto_promote,
             type_id, target_type, target_ref, ephemeral)
        values
            (:programId, :key, :name, :description, :displayOrder, :requiresApproval, :autoPromote,
             :typeId, (:targetType)::workops.environment_target_type,
             :targetRef, :ephemeral)
        returning *
        """
    )
    suspend fun add(
        programId: UUID,
        key: String,
        name: String,
        description: String?,
        displayOrder: Int,
        requiresApproval: Boolean,
        autoPromote: Boolean,
        typeId: UUID,
        targetType: EnvironmentTargetType,
        targetRef: String?,
        ephemeral: Boolean,
    ): Environment

    @Query(
        """
        update workops.environment
        set name = :name, description = :description, display_order = :displayOrder,
            requires_approval = :requiresApproval, auto_promote = :autoPromote,
            type_id = :typeId,
            target_type = (:targetType)::workops.environment_target_type,
            target_ref = :targetRef, ephemeral = :ephemeral,
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun update(
        id: UUID,
        name: String,
        description: String?,
        displayOrder: Int,
        requiresApproval: Boolean,
        autoPromote: Boolean,
        typeId: UUID,
        targetType: EnvironmentTargetType,
        targetRef: String?,
        ephemeral: Boolean,
        expectedVersion: Long,
    ): Environment?

    @Query("select * from workops.environment_promotion_source where environment_id = :environmentId")
    suspend fun listSources(environmentId: UUID): List<EnvironmentPromotionSource>

    @Query(
        """
        insert into workops.environment_promotion_source (environment_id, source_environment_id)
        values (:environmentId, :sourceId)
        on conflict do nothing
        """
    )
    suspend fun addSource(environmentId: UUID, sourceId: UUID)

    @Query("delete from workops.environment_promotion_source where environment_id = :environmentId")
    suspend fun clearSources(environmentId: UUID)

    @Query("delete from workops.environment where id = :id")
    suspend fun delete(id: UUID)
}

@Repository
interface EnvironmentDeploymentRepository {

    @Query("select * from workops.environment_deployment where id = :id")
    suspend fun getById(id: UUID): EnvironmentDeployment?

    @Query("select * from workops.environment_deployment where environment_id = :environmentId order by deployed_at desc")
    suspend fun listByEnvironment(environmentId: UUID): List<EnvironmentDeployment>

    @Query("select * from workops.environment_deployment where release_id = :releaseId order by deployed_at desc nulls last")
    suspend fun listByRelease(releaseId: UUID): List<EnvironmentDeployment>

    @Query(
        """
        select distinct on (project_id, target_kind) *
        from workops.environment_deployment
        where environment_id = :environmentId and status = 'DEPLOYED'
        order by project_id, target_kind, deployed_at desc
        """
    )
    suspend fun currentState(environmentId: UUID): List<EnvironmentDeployment>

    @Query(
        """
        insert into workops.environment_deployment
            (environment_id, project_id, target_kind, version_id, release_id,
             artifact_publication_id, app_build_number_allocation_id, health_check_url, previous_deployment_id)
        values
            (:environmentId, :projectId, (:targetKind)::workops.deploy_target_kind, :versionId, :releaseId,
             :artifactPublicationId, :appBuildNumberAllocationId, :healthCheckUrl, :previousDeploymentId)
        returning *
        """
    )
    suspend fun add(
        environmentId: UUID,
        projectId: UUID,
        targetKind: bosca.workops.deploy.DeployTargetKind,
        versionId: UUID,
        releaseId: UUID?,
        artifactPublicationId: UUID?,
        appBuildNumberAllocationId: UUID?,
        healthCheckUrl: String?,
        previousDeploymentId: UUID?,
    ): EnvironmentDeployment

    @Query(
        """
        update workops.environment_deployment
        set status = :status, deployed_at = :deployedAt,
            deployed_by_principal_id = :deployedByPrincipalId,
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun updateStatus(
        id: UUID,
        status: String,
        deployedAt: OffsetDateTime?,
        deployedByPrincipalId: UUID?,
        expectedVersion: Long,
    ): EnvironmentDeployment?

    @Query(
        """
        update workops.environment_deployment
        set health_check_status = :healthCheckStatus,
            last_health_check_at = now(),
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun updateHealthCheck(id: UUID, healthCheckStatus: String, expectedVersion: Long): EnvironmentDeployment?
}

// ── Compatibility Test Result ─────────────────────────────────────────

@Repository
interface CompatibilityTestResultRepository {

    @Query("select * from workops.compatibility_test_result where id = :id")
    suspend fun getById(id: UUID): CompatibilityTestResult?

    @Query(
        """
        select * from workops.compatibility_test_result
        where consumer_project_id = :consumerProjectId and consumer_version_id = :consumerVersionId
        order by tested_at desc
        """
    )
    suspend fun listByConsumerVersion(consumerProjectId: UUID, consumerVersionId: UUID): List<CompatibilityTestResult>

    @Query(
        """
        select * from workops.compatibility_test_result
        where provider_project_id = :providerProjectId and provider_version_id = :providerVersionId
        order by tested_at desc
        """
    )
    suspend fun listByProviderVersion(providerProjectId: UUID, providerVersionId: UUID): List<CompatibilityTestResult>

    @Query(
        """
        insert into workops.compatibility_test_result
            (consumer_project_id, consumer_version_id, provider_project_id, provider_version_id,
             test_suite, status, breaking_changes_detected, pipeline_run_id, result_url)
        values
            (:consumerProjectId, :consumerVersionId, :providerProjectId, :providerVersionId,
             :testSuite, :status, :breakingChangesDetected, :pipelineRunId, :resultUrl)
        returning *
        """
    )
    suspend fun add(
        consumerProjectId: UUID,
        consumerVersionId: UUID,
        providerProjectId: UUID,
        providerVersionId: UUID,
        testSuite: String,
        status: String,
        breakingChangesDetected: Boolean,
        pipelineRunId: UUID?,
        resultUrl: String?,
    ): CompatibilityTestResult
}

// ── Release Component Version Deployment ──────────────────────────────

@Repository
interface ReleaseProjectVersionDeploymentRepository {

    @Query(
        """
        update workops.release_component_version
        set deployment_order = :deploymentOrder
        where release_id = :releaseId and project_id = :projectId and version_id = :versionId
        returning *
        """
    )
    suspend fun setDeploymentOrder(
        releaseId: UUID,
        projectId: UUID,
        versionId: UUID,
        deploymentOrder: Int,
    ): bosca.workops.model.release.ReleaseProjectVersion?

    @Query(
        """
        update workops.release_component_version
        set deployment_status = :deploymentStatus,
            deployed_at = :deployedAt,
            deployed_by_principal_id = :deployedByPrincipalId
        where release_id = :releaseId and project_id = :projectId and version_id = :versionId
        returning *
        """
    )
    suspend fun updateDeploymentStatus(
        releaseId: UUID,
        projectId: UUID,
        versionId: UUID,
        deploymentStatus: String,
        deployedAt: OffsetDateTime?,
        deployedByPrincipalId: UUID?,
    ): bosca.workops.model.release.ReleaseProjectVersion?

    @Query(
        """
        update workops.release_component_version
        set rollback_version_id = :rollbackVersionId
        where release_id = :releaseId and project_id = :projectId and version_id = :versionId
        returning *
        """
    )
    suspend fun setRollbackVersion(
        releaseId: UUID,
        projectId: UUID,
        versionId: UUID,
        rollbackVersionId: UUID?,
    ): bosca.workops.model.release.ReleaseProjectVersion?
}

// ── Release Notes Task Query ──────────────────────────────────────────

@Repository
interface ReleaseNotesTaskRepository {

    @Query(
        """
        select * from workops.task
        where :versionId = any(fix_version_ids)
          and deleted_at is null
        order by project_id, key
        """
    )
    suspend fun listTasksByFixVersion(versionId: UUID): List<bosca.workops.model.task.Task>
}

// ── Release Notes ─────────────────────────────────────────────────────

@Repository
interface ReleaseNotesRepository {

    @Query("select * from workops.release_notes where release_id = :releaseId")
    suspend fun getByRelease(releaseId: UUID): ReleaseNotes?

    @Query(
        """
        insert into workops.release_notes (release_id, sections)
        values (:releaseId, cast(:sections as jsonb))
        on conflict (release_id) do update
        set sections = cast(:sections as jsonb), generated_at = now(),
            manually_edited = false, version = workops.release_notes.version + 1
        returning *
        """
    )
    suspend fun upsert(releaseId: UUID, sections: String): ReleaseNotes

    @Query(
        """
        update workops.release_notes
        set sections = cast(:sections as jsonb), manually_edited = true, version = version + 1
        where release_id = :releaseId and version = :expectedVersion
        returning *
        """
    )
    suspend fun editManually(releaseId: UUID, sections: String, expectedVersion: Long): ReleaseNotes?
}
