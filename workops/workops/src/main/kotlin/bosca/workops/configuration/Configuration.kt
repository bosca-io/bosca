package bosca.workops.configuration

import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.forms.service.FormSubmissionProcessor
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.workops.migration.WorkOpsMigration
import bosca.workops.service.EnvironmentPermissionEvaluator
import bosca.workops.service.EnvironmentService
import bosca.workops.service.HmacSecret
import bosca.workops.service.PortfolioPermissionEvaluator
import bosca.workops.service.PortfolioService
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.RequirementPermissionEvaluator
import bosca.workops.service.RequirementService
import bosca.workops.service.SpecPermissionEvaluator
import bosca.workops.service.SpecService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
import bosca.workops.service.WorkOpsFormSubmissionProcessor
import bosca.workops.service.WorkflowEvaluator

/**
 * Wires the Work Ops module's runtime dependencies into Bosca's DI container.
 */
@Providers
class Configuration {

    @Provider(name = "workops-migrations")
    fun migration(): Migration = WorkOpsMigration()

    /** The Google Play deploy-target adapter — resolved by `provide<DeployTarget>(name = "GOOGLE_PLAY")`. */
    @Provider(name = "GOOGLE_PLAY", singleton = true)
    fun googlePlayDeployTarget(
        environmentService: bosca.workops.service.EnvironmentService,
        secrets: bosca.pipelines.service.PipelineSecretService,
        artifacts: bosca.artifacts.service.ArtifactRepositoryService,
        blobs: bosca.artifacts.service.BlobStorageService,
        publisher: bosca.store.pipelines.PlayPublisher,
        releaseNotes: bosca.workops.service.ReleaseNotesService,
    ): bosca.workops.deploy.DeployTarget =
        bosca.workops.deploy.GooglePlayDeployTarget(environmentService, secrets, artifacts, blobs, publisher, releaseNotes)

    /** Verifies a CI job's declared artifacts actually landed in the registry — the git-ci run
     *  finalizer fails the job when they didn't. */
    @Provider(singleton = true)
    fun producedArtifactVerifier(
        artifacts: bosca.artifacts.service.ArtifactRepositoryService,
    ): bosca.git.service.ProducedArtifactVerifier =
        bosca.workops.service.RegistryProducedArtifactVerifier(artifacts)

    /** Verifies a CI job's REQUIRED artifacts exist in the registry — the git-ci requirement checker
     *  gates job dispatch on this. Same registry resolution as the produced verifier. */
    @Provider(singleton = true)
    fun requiredArtifactVerifier(
        artifacts: bosca.artifacts.service.ArtifactRepositoryService,
    ): bosca.git.service.RequiredArtifactVerifier =
        bosca.workops.service.RegistryProducedArtifactVerifier(artifacts)

    /** Executes git-ci's `uses: deploy` action: deploy.yaml target selection, artifact
     *  routing, environment permission against the initiating principal, adapter invocation. */
    @Provider(singleton = true)
    fun releaseDeployer(
        deployConfigService: bosca.workops.deploy.DeployConfigService,
        projectRepositories: bosca.workops.service.ProjectRepositoryService,
        projectService: bosca.workops.service.ProjectService,
        versionService: bosca.workops.service.VersionService,
        releaseService: bosca.workops.service.ReleaseService,
        publications: bosca.workops.service.ArtifactPublicationService,
        environmentService: EnvironmentService,
        environmentAuthorizer: bosca.git.service.EnvironmentActionAuthorizer,
        programService: ProgramService,
        programPermissionEvaluator: ProgramPermissionEvaluator,
        securityService: SecurityService,
        appBuildNumbers: bosca.workops.service.AppBuildNumberService,
        repositoryBrowse: bosca.git.service.RepositoryBrowseService,
        secrets: bosca.pipelines.service.PipelineSecretService,
        playPublisher: bosca.store.pipelines.PlayPublisher,
        appStorePublisher: bosca.store.pipelines.AppStorePublisher,
        releaseNotesService: bosca.workops.service.ReleaseNotesService,
    ): bosca.git.service.ReleaseDeployer =
        bosca.workops.service.GitReleaseDeployer(
            deployConfigService, projectRepositories, projectService, versionService, releaseService,
            publications, environmentService, environmentAuthorizer, programService,
            programPermissionEvaluator, securityService, appBuildNumbers, repositoryBrowse,
            secrets, playPublisher, appStorePublisher,
            releaseNotesService,
        )

    /** Authorizes git-ci's environment-targeting actions (approve/deploy/promote/rollback) against
     *  the linked WorkOps environment's own permission rows. */
    @Provider(singleton = true)
    fun environmentActionAuthorizer(
        projectRepositories: bosca.workops.service.ProjectRepositoryService,
        projectService: bosca.workops.service.ProjectService,
        environmentService: EnvironmentService,
        environmentPermissionEvaluator: EnvironmentPermissionEvaluator,
    ): bosca.git.service.EnvironmentActionAuthorizer =
        bosca.workops.service.GitEnvironmentActionAuthorizer(
            projectRepositories, projectService, environmentService, environmentPermissionEvaluator,
        )

    /** The App Store deploy-target adapter — resolved by `provide<DeployTarget>(name = "APP_STORE")`. */
    @Provider(name = "APP_STORE", singleton = true)
    fun appStoreDeployTarget(
        environmentService: bosca.workops.service.EnvironmentService,
        secrets: bosca.pipelines.service.PipelineSecretService,
        publisher: bosca.store.pipelines.AppStorePublisher,
        releaseNotes: bosca.workops.service.ReleaseNotesService,
    ): bosca.workops.deploy.DeployTarget =
        bosca.workops.deploy.AppStoreDeployTarget(environmentService, secrets, publisher, releaseNotes)

    @Provider(singleton = true)
    fun workflowEvaluator(): WorkflowEvaluator = WorkflowEvaluator()

    @Provider(singleton = true)
    fun hmacSecret(): HmacSecret = HmacSecret.random()

    @Provider(singleton = true)
    fun portfolioPermissionEvaluator(
        service: PortfolioService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator,
    ) = PortfolioPermissionEvaluator(service, securityService, groupEvaluator)

    @Provider(singleton = true)
    fun programPermissionEvaluator(
        service: ProgramService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator,
    ) = ProgramPermissionEvaluator(service, securityService, groupEvaluator)

    @Provider(singleton = true)
    fun projectPermissionEvaluator(
        service: ProjectService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator,
    ) = ProjectPermissionEvaluator(service, securityService, groupEvaluator)

    @Provider(singleton = true)
    fun taskPermissionEvaluator(
        service: TaskService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator,
    ) = TaskPermissionEvaluator(service, securityService, groupEvaluator)

    @Provider(singleton = true)
    fun specPermissionEvaluator(
        service: SpecService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator,
    ) = SpecPermissionEvaluator(service, securityService, groupEvaluator)

    @Provider(singleton = true)
    fun environmentPermissionEvaluator(
        service: EnvironmentService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator,
    ) = EnvironmentPermissionEvaluator(service, securityService, groupEvaluator)

    @Provider(singleton = true)
    fun requirementPermissionEvaluator(
        service: RequirementService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator,
    ) = RequirementPermissionEvaluator(service, securityService, groupEvaluator)

    @Provider(singleton = true, name = "workops-form-submission-processor")
    fun workOpsFormSubmissionProcessor(
        taskService: TaskService,
        projectService: ProjectService,
    ): FormSubmissionProcessor = WorkOpsFormSubmissionProcessor(taskService, projectService)
}
