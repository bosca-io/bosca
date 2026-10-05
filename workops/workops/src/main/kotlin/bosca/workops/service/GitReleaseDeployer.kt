package bosca.workops.service

import bosca.di.MissingProviderException
import bosca.di.provide
import bosca.git.service.EnvironmentActionAuthorizer
import bosca.git.service.ReleaseDeployOutcome
import bosca.git.service.ReleaseDeployRequest
import bosca.git.service.ReleaseDeployer
import bosca.git.service.RepositoryBrowseService
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.serialization.UUID
import bosca.store.pipelines.AppStorePublisher
import bosca.store.pipelines.AppStoreReviewMode
import bosca.store.pipelines.PlayPublisher
import bosca.workops.deploy.AppStoreTargetConfig
import bosca.workops.deploy.ArtifactSelector
import bosca.workops.deploy.DeployArtifact
import bosca.workops.deploy.DeployBuildNumber
import bosca.workops.deploy.DeployConfigService
import bosca.workops.deploy.DeployRequest
import bosca.workops.deploy.DeployTarget
import bosca.workops.deploy.DeployTargetEntry
import bosca.workops.deploy.DeployTargetKind
import bosca.workops.deploy.RolloutRequest
import bosca.workops.deploy.GooglePlayTargetConfig
import bosca.workops.model.project.Project
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * The WorkOps-backed [ReleaseDeployer]: executes `uses: deploy` end-to-end. Reads the
 * target repository's `.bosca/deploy.yaml` (run ref first — config versions with the release — then
 * the version tags, then main), selects the environment's target entry, resolves the workops
 * project/version/publication, verifies the initiating principal's EXECUTE on the environment, and
 * invokes the [DeployTarget] adapter — which records the EnvironmentDeployment, wherever the deploy
 * was invoked from.
 *
 * Everything unresolvable fails loudly by name: an undeclared environment, an ambiguous or unknown
 * target, an artifact selector matching nothing the version's builds declared, a missing workops
 * version. Step-level overrides win over the deploy.yaml entry's config.
 */
class GitReleaseDeployer(
    private val deployConfigService: DeployConfigService,
    private val projectRepositories: ProjectRepositoryService,
    private val projectService: ProjectService,
    private val versionService: VersionService,
    private val releaseService: ReleaseService,
    private val publications: ArtifactPublicationService,
    private val environmentService: EnvironmentService,
    private val environmentAuthorizer: EnvironmentActionAuthorizer,
    private val programService: ProgramService,
    private val programPermissionEvaluator: ProgramPermissionEvaluator,
    private val securityService: SecurityService,
    private val appBuildNumbers: AppBuildNumberService,
    private val repositoryBrowse: RepositoryBrowseService,
    private val secrets: bosca.pipelines.service.PipelineSecretService,
    private val playPublisher: PlayPublisher,
    private val appStorePublisher: AppStorePublisher,
    private val releaseNotesService: ReleaseNotesService,
) : ReleaseDeployer {

    override suspend fun allocateBuildNumber(
        request: bosca.git.service.ReleaseBuildNumberRequest,
    ): bosca.git.service.ReleaseBuildNumberOutcome {
        val platform = runCatching {
            bosca.workops.model.artifact.AppBuildPlatform.valueOf(request.platform.trim().uppercase())
        }.getOrElse {
            throw IllegalArgumentException("platform must be 'android' or 'ios', got '${request.platform}'")
        }
        val result = appBuildNumbers.allocate(
            bosca.workops.model.artifact.AllocateAppBuildNumberInput(
                platform = platform,
                applicationId = request.applicationId,
                buildKey = request.buildKey,
                repositoryId = request.repositoryId,
                sourceCommitSha = request.sourceCommitSha,
                sourceVersion = request.sourceVersion,
                pipelineRunId = request.pipelineRunId,
                minimumNumber = request.minimum?.let(platform::parse) ?: 1,
            ),
        )
        return bosca.git.service.ReleaseBuildNumberOutcome(
            number = result.allocation.number,
            value = result.allocation.value,
            reused = result.reused,
        )
    }

    override suspend fun deploy(request: ReleaseDeployRequest): ReleaseDeployOutcome {
        val initiator = securityService.impersonate(request.initiatorPrincipalId)
        environmentAuthorizer.verifyAllowed(
            initiator, request.targetRepositoryId, request.environmentKey, PermissionAction.EXECUTE,
        )

        val project = resolveProject(request.targetRepositoryId)
        val environment = environmentService.getByProgramAndKey(project.programId, request.environmentKey)
            ?: environmentService.getByProgramAndName(project.programId, request.environmentKey)
            ?: throw IllegalStateException(
                "Environment '${request.environmentKey}' does not exist in program ${project.programId} — " +
                    "sync the release pipeline before deploying"
            )

        val versionName = request.parameters[RELEASE_VERSION_PARAMETER]
        val entry = resolveTargetEntry(
            request.targetRepositoryId, request.ref, request.environmentKey, request.target, versionName,
        )
        val kind = entry.targetKind()
            ?: throw IllegalStateException("Environment '${request.environmentKey}' declares unknown target '${entry.target}'")

        val version = resolveVersion(project, request.parameters, versionName)
        val config = mergeConfig(entry.config, request.overrides)
        val artifact = overrideSelector(entry.artifact, request.overrides)?.let {
            resolveArtifact(it, version.id, versionName)
        }
        val buildNumber = resolveBuildNumber(
            kind, config, request.targetRepositoryId, request.ref, version.name,
        )

        val adapter = try {
            provide<DeployTarget>(name = kind.name)
        } catch (e: MissingProviderException) {
            throw IllegalStateException("No '${entry.target}' deploy adapter is available in this deployment")
        }
        val outcome = adapter.deploy(
            DeployRequest(
                environmentId = environment.id,
                projectId = project.id,
                versionId = version.id,
                version = version.name,
                config = config,
                deployedByPrincipalId = request.initiatorPrincipalId,
                releaseId = request.parameters[RELEASE_ID_PARAMETER]?.let { UUID.parse(it) },
                artifactPublicationId = artifact?.publicationId,
                artifact = artifact,
                buildNumber = buildNumber,
                configRepositoryId = request.targetRepositoryId,
            ),
            initiator,
        )
        log.info(
            "Deployed '{}' to environment '{}' via {} — {} ({})",
            version.name, request.environmentKey, entry.target, outcome.reference, outcome.status,
        )
        return ReleaseDeployOutcome(reference = outcome.reference, status = outcome.status.name)
    }

    override suspend fun rollback(request: bosca.git.service.ReleaseRollbackRequest): ReleaseDeployOutcome {
        val initiator = securityService.impersonate(request.initiatorPrincipalId)
        environmentAuthorizer.verifyAllowed(
            initiator, request.targetRepositoryId, request.environmentKey, PermissionAction.EXECUTE,
        )
        val project = resolveProject(request.targetRepositoryId)
        val environment = environmentService.getByProgramAndKey(project.programId, request.environmentKey)
            ?: environmentService.getByProgramAndName(project.programId, request.environmentKey)
            ?: throw IllegalStateException(
                "Environment '${request.environmentKey}' does not exist in program ${project.programId}"
            )
        val versionName = request.parameters[RELEASE_VERSION_PARAMETER]
        val entry = resolveTargetEntry(
            request.targetRepositoryId, request.ref, request.environmentKey, request.target, versionName,
        )
        val kind = entry.targetKind()
            ?: throw IllegalStateException("Environment '${request.environmentKey}' declares unknown target '${entry.target}'")
        val selector = overrideSelector(entry.artifact, request.overrides)
        val config = mergeConfig(entry.config, request.overrides)
        val needsBuildNumber = kind == DeployTargetKind.GOOGLE_PLAY || kind == DeployTargetKind.APP_STORE
        val artifactResolution = selector?.let {
            val version = resolveVersion(project, request.parameters, versionName)
            resolveArtifact(it, version.id, versionName) to version
        }
        val buildVersion = when {
            !needsBuildNumber -> null
            artifactResolution != null -> artifactResolution.second
            else -> resolveVersion(project, request.parameters, versionName)
        }
        val artifact = artifactResolution?.first
        val buildNumber = buildVersion?.let {
            resolveBuildNumber(kind, config, request.targetRepositoryId, request.ref, it.name)
        }
        val adapter = try {
            provide<DeployTarget>(name = kind.name)
        } catch (e: MissingProviderException) {
            throw IllegalStateException("No '${entry.target}' deploy adapter is available in this deployment")
        }
        val outcome = adapter.rollback(
            bosca.workops.deploy.RollbackRequest(
                environmentId = environment.id,
                projectId = project.id,
                toRevision = request.toRevision,
                config = config,
                deployedByPrincipalId = request.initiatorPrincipalId,
                version = buildVersion?.name.orEmpty(),
                artifact = artifact,
                buildNumber = buildNumber,
                configRepositoryId = request.targetRepositoryId,
            ),
            initiator,
        )
        log.info(
            "Rolled back environment '{}' via {} — {} ({})",
            request.environmentKey, entry.target, outcome.reference, outcome.status,
        )
        return ReleaseDeployOutcome(reference = outcome.reference, status = outcome.status.name)
    }

    override suspend fun playRollout(request: bosca.git.service.ReleasePlayRolloutRequest): ReleaseDeployOutcome {
        require(request.rolloutPercentage in 0.0..100.0) {
            "rolloutPercentage must be between 0 and 100, got ${request.rolloutPercentage}"
        }
        val initiator = securityService.impersonate(request.initiatorPrincipalId)
        environmentAuthorizer.verifyAllowed(
            initiator, request.targetRepositoryId, request.environmentKey, PermissionAction.EXECUTE,
        )
        val project = resolveProject(request.targetRepositoryId)
        val environment = environmentService.getByProgramAndKey(project.programId, request.environmentKey)
            ?: environmentService.getByProgramAndName(project.programId, request.environmentKey)
            ?: throw IllegalStateException(
                "Environment '${request.environmentKey}' does not exist in program ${project.programId}"
            )
        val versionName = request.parameters[RELEASE_VERSION_PARAMETER]
        val entry = resolveTargetEntry(
            request.targetRepositoryId, request.ref, request.environmentKey, request.target, versionName,
        )
        val kind = entry.targetKind()
            ?: throw IllegalStateException("Environment '${request.environmentKey}' declares unknown target '${entry.target}'")
        require(kind == DeployTargetKind.GOOGLE_PLAY) {
            "play-rollout requires a google_play target, but '${entry.target}' resolves to $kind"
        }
        val selector = entry.artifact
            ?: throw IllegalStateException(
                "Environment '${request.environmentKey}' google_play target requires an artifact selector"
            )
        val version = resolveVersion(project, request.parameters, versionName)
        val artifact = resolveArtifact(selector, version.id, versionName)
        val buildNumber = resolveBuildNumber(
            kind, entry.config, request.targetRepositoryId, request.ref, version.name,
        )
        val adapter = try {
            provide<DeployTarget>(name = kind.name)
        } catch (e: MissingProviderException) {
            throw IllegalStateException("No '${entry.target}' deploy adapter is available in this deployment")
        }
        val outcome = adapter.rollout(
            RolloutRequest(
                environmentId = environment.id,
                projectId = project.id,
                rolloutPercentage = request.rolloutPercentage,
                config = entry.config,
                deployedByPrincipalId = request.initiatorPrincipalId,
                artifact = artifact,
                buildNumber = buildNumber,
                configRepositoryId = request.targetRepositoryId,
            ),
            initiator,
        )
        log.info(
            "Advanced environment '{}' Play rollout to {}% — {} ({})",
            request.environmentKey, request.rolloutPercentage, outcome.reference, outcome.status,
        )
        return ReleaseDeployOutcome(reference = outcome.reference, status = outcome.status.name)
    }

    override suspend fun appStoreReview(
        request: bosca.git.service.ReleaseAppStoreReviewRequest,
    ): bosca.git.service.ReleaseAppStoreReviewOutcome {
        val initiator = securityService.impersonate(request.initiatorPrincipalId)
        environmentAuthorizer.verifyAllowed(
            initiator, request.targetRepositoryId, request.environmentKey, PermissionAction.EXECUTE,
        )
        val project = resolveProject(request.targetRepositoryId)
        requireEnvironment(project, request.environmentKey)
        val versionName = request.parameters[RELEASE_VERSION_PARAMETER]
        val entry = resolveTargetEntry(
            request.targetRepositoryId, request.ref, request.environmentKey, request.target, versionName,
        )
        require(entry.targetKind() == DeployTargetKind.APP_STORE) {
            "app-store-review requires an app_store target, but '${entry.target}' resolves to ${entry.targetKind()}"
        }
        val version = resolveVersion(project, request.parameters, versionName)
        val cfg = json.decodeFromJsonElement(AppStoreTargetConfig.serializer(), entry.config)
        val buildNumber = resolveBuildNumber(
            DeployTargetKind.APP_STORE, entry.config, request.targetRepositoryId, request.ref, version.name,
        ) ?: error("app-store-review requires a durable iOS build number")
        val credential = secrets.resolve(cfg.ascKeySecret)
            ?: error("pipeline secret '${cfg.ascKeySecret}' (the App Store Connect API key) is not set")
        val mode = when (request.mode) {
            bosca.git.service.ReleaseAppStoreReviewMode.BETA -> AppStoreReviewMode.BETA
            bosca.git.service.ReleaseAppStoreReviewMode.APP_STORE -> AppStoreReviewMode.APP_STORE
        }
        val state = appStorePublisher.reviewState(
            credential, cfg.bundleId, version.name, buildNumber.value, mode,
        )
        val approved = state in when (mode) {
            AppStoreReviewMode.BETA -> BETA_APPROVED_STATES
            AppStoreReviewMode.APP_STORE -> APP_STORE_APPROVED_STATES
        }
        val rejected = state in when (mode) {
            AppStoreReviewMode.BETA -> BETA_REJECTED_STATES
            AppStoreReviewMode.APP_STORE -> APP_STORE_REJECTED_STATES
        }
        return bosca.git.service.ReleaseAppStoreReviewOutcome(
            state = state,
            complete = approved || rejected,
            approved = approved,
        )
    }

    override suspend fun storeHealth(
        request: bosca.git.service.ReleaseStoreHealthRequest,
    ): bosca.git.service.ReleaseStoreHealthOutcome {
        require(request.maxCrashRate.isFinite() && request.maxCrashRate in 0.0..100.0) {
            "maxCrashRate must be between 0 and 100 percent, got ${request.maxCrashRate}"
        }
        require(!request.window.isZero && !request.window.isNegative) { "store health window must be positive" }
        val initiator = securityService.impersonate(request.initiatorPrincipalId)
        environmentAuthorizer.verifyAllowed(
            initiator, request.targetRepositoryId, request.environmentKey, PermissionAction.EXECUTE,
        )
        val project = resolveProject(request.targetRepositoryId)
        requireEnvironment(project, request.environmentKey)
        val versionName = request.parameters[RELEASE_VERSION_PARAMETER]
        val entry = resolveTargetEntry(
            request.targetRepositoryId, request.ref, request.environmentKey, request.target, versionName,
        )
        require(entry.targetKind() == DeployTargetKind.GOOGLE_PLAY) {
            "verify-store-health currently requires a google_play target, but '${entry.target}' resolves to ${entry.targetKind()}"
        }
        val version = resolveVersion(project, request.parameters, versionName)
        val cfg = json.decodeFromJsonElement(GooglePlayTargetConfig.serializer(), entry.config)
        val buildNumber = resolveBuildNumber(
            DeployTargetKind.GOOGLE_PLAY, entry.config, request.targetRepositoryId, request.ref, version.name,
        ) ?: error("verify-store-health requires a durable Android build number")
        val credential = secrets.resolve(cfg.serviceAccountSecret)
            ?: error("pipeline secret '${cfg.serviceAccountSecret}' (the Play service account) is not set")
        val result = playPublisher.crashRate(
            credential, cfg.packageName, buildNumber.number, request.window,
        )
        return bosca.git.service.ReleaseStoreHealthOutcome(
            crashRate = result.crashRate,
            maxCrashRate = request.maxCrashRate,
            windowSeconds = request.window.seconds,
            healthy = result.crashRate <= request.maxCrashRate,
        )
    }

    override suspend fun deploymentHealth(
        targetRepositoryId: UUID,
        environmentKey: String,
        initiatorPrincipalId: UUID,
    ): String {
        val initiator = securityService.impersonate(initiatorPrincipalId)
        environmentAuthorizer.verifyAllowed(
            initiator, targetRepositoryId, environmentKey, PermissionAction.EXECUTE,
        )
        val project = resolveProject(targetRepositoryId)
        val environment = environmentService.getByProgramAndKey(project.programId, environmentKey)
            ?: environmentService.getByProgramAndName(project.programId, environmentKey)
            ?: throw IllegalStateException("Environment '$environmentKey' does not exist in program ${project.programId}")
        val deployments = environmentService.currentState(environment.id).filter { it.projectId == project.id }
        if (deployments.isEmpty()) return NO_DEPLOYMENT_HEALTH
        return deployments
            .map { environmentService.probeHealth(it.id, HttpHealthProbe::probe) }
            .maxBy { healthSeverity(it) }
            .name
    }

    override suspend fun markReleased(releaseId: UUID, initiatorPrincipalId: UUID) {
        val initiator = securityService.impersonate(initiatorPrincipalId)
        val release = releaseService.getById(releaseId)
            ?: throw NoSuchElementException("Release not found: $releaseId")
        val program = programService.getById(release.programId)
            ?: throw NoSuchElementException("Program not found: ${release.programId}")
        programPermissionEvaluator.verifyAllowed(initiator, program, PermissionAction.MANAGE)
        // Idempotent by design (same guard as the relay's Mark Released node): a re-run job over an
        // already-released release is a no-op, not an error.
        if (release.releasedAt == null) {
            releaseService.release(release.id, release.version)
            log.info("Marked release '{}' ({}) released", release.name, release.id)
        }
    }

    override suspend fun generateReleaseNotes(releaseId: UUID, initiatorPrincipalId: UUID) {
        val initiator = securityService.impersonate(initiatorPrincipalId)
        val release = releaseService.getById(releaseId)
            ?: throw NoSuchElementException("Release not found: $releaseId")
        val program = programService.getById(release.programId)
            ?: throw NoSuchElementException("Program not found: ${release.programId}")
        programPermissionEvaluator.verifyAllowed(initiator, program, PermissionAction.MANAGE)
        releaseNotesService.autoGenerateLocalized(releaseId, initiatorPrincipalId)
        log.info("Generated localized version release notes for '{}' ({})", release.name, release.id)
    }

    /** The workops project owning the target repository — the deploy's subject. */
    private suspend fun resolveProject(repositoryId: UUID): Project {
        val links = projectRepositories.listByRepository(repositoryId)
        val projects = links.mapNotNull { projectService.getById(it.projectId) }
        return projects.firstOrNull()
            ?: throw IllegalStateException(
                "Repository $repositoryId is not linked to any WorkOps project — nothing to deploy against"
            )
    }

    private suspend fun requireEnvironment(project: Project, environmentKey: String) {
        val byKey = environmentService.getByProgramAndKey(project.programId, environmentKey)
        if (byKey != null) return
        val byName = environmentService.getByProgramAndName(project.programId, environmentKey)
        if (byName != null) return
        throw IllegalStateException("Environment '$environmentKey' does not exist in program ${project.programId}")
    }

    /** Reads deploy.yaml at the first candidate ref that has one and selects the target entry. */
    private suspend fun resolveTargetEntry(
        targetRepositoryId: UUID,
        ref: String,
        environmentKey: String,
        requested: String?,
        versionName: String?,
    ): DeployTargetEntry {
        val refs = buildList {
            versionName?.takeIf { it.isNotBlank() }?.let {
                add("refs/tags/$it")
            }
            add(ref)
            add("refs/heads/main")
        }.distinct()
        val config = refs.firstNotNullOfOrNull { candidate -> deployConfigService.read(targetRepositoryId, candidate) }
            ?: throw IllegalStateException(
                "Repository $targetRepositoryId has no ${DeployConfigService.DEPLOY_CONFIG_PATH} at any of $refs"
            )
        val declaration = config.forEnvironment(environmentKey)
            ?: throw IllegalStateException(
                "${DeployConfigService.DEPLOY_CONFIG_PATH} declares no environment '$environmentKey'"
            )
        val entries = declaration.effectiveTargets()
        return if (requested == null) {
            entries.singleOrNull() ?: throw IllegalStateException(
                "Environment '$environmentKey' declares ${entries.size} targets " +
                    "(${entries.joinToString { it.target }}) — name one with 'target:'"
            )
        } else {
            entries.firstOrNull { it.target.replace('-', '_').equals(requested.replace('-', '_'), ignoreCase = true) }
                ?: throw IllegalStateException(
                    "Environment '$environmentKey' declares no target '$requested' — " +
                        "available: ${entries.joinToString { it.target }}"
                )
        }
    }

    /**
     * The workops Version being deployed: the release's bundled version for this project when
     * `release.id` rides the run, else the project's version named `release.version`. A deploy
     * without either cannot record honestly and fails.
     */
    private suspend fun resolveVersion(
        project: Project,
        parameters: Map<String, String>,
        versionName: String?,
    ): bosca.workops.model.version.Version {
        parameters[RELEASE_ID_PARAMETER]?.let { releaseId ->
            val bundled = releaseService.listVersions(UUID.parse(releaseId))
                .firstOrNull { it.projectId == project.id }
            if (bundled != null) {
                return versionService.getById(bundled.versionId)
                    ?: throw IllegalStateException("Release $releaseId bundles a missing version ${bundled.versionId}")
            }
        }
        val name = versionName?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "Deploy needs a workops Version: run with release.id/release.version parameters " +
                    "(project '${project.key}' cannot record a deployment without one)"
            )
        return versionService.listByProject(project.id).firstOrNull { it.name == name }
            ?: throw IllegalStateException(
                "Project '${project.key}' has no workops Version '$name' — bundle the release or create the version"
            )
    }

    /**
     * The declared artifact this target ships, resolved against the version's publications. A
     * selector matching nothing fails naming itself — never a silent skip. Callers decide whether a
     * target has a selector before invoking this non-null resolver.
     */
    private suspend fun resolveArtifact(
        selector: ArtifactSelector,
        versionId: UUID,
        versionName: String?,
    ): DeployArtifact {
        val coordinate = interpolate(selector.coordinate, versionName)
        val candidates = publications.listByVersion(versionId)
        val type = selector.type
        val publication = candidates.firstOrNull { publication ->
            publication.coordinates == coordinate &&
                (type == null ||
                    publication.artifactType.name.equals(type.replace('-', '_'), ignoreCase = true)) &&
                (selector.namespace == null || publication.namespace == selector.namespace)
        } ?: throw IllegalStateException(
            "Artifact selector {type=${selector.type}, namespace=${selector.namespace}, " +
                "coordinate=$coordinate} matches none of the version's " +
                "${candidates.size} declared " +
                "publication(s): ${candidates.joinToString { it.coordinates }}"
        )
        return DeployArtifact(
            publicationId = publication.id,
            type = selector.type ?: publication.artifactType.name,
            namespace = selector.namespace ?: publication.namespace,
            coordinate = publication.coordinates,
        )
    }

    /** Step-level `artifact.*` overrides refine (or introduce) the entry's selector. */
    private fun overrideSelector(declared: ArtifactSelector?, overrides: Map<String, String>): ArtifactSelector? {
        val coordinate = overrides[ARTIFACT_COORDINATE_OVERRIDE] ?: declared?.coordinate ?: return null
        return ArtifactSelector(
            type = overrides[ARTIFACT_TYPE_OVERRIDE] ?: declared?.type,
            namespace = overrides[ARTIFACT_NAMESPACE_OVERRIDE] ?: declared?.namespace,
            coordinate = coordinate,
        )
    }

    /** Resolves the immutable store build identity allocated by the producing build pipeline. */
    private suspend fun resolveBuildNumber(
        kind: DeployTargetKind,
        config: JsonElement,
        repositoryId: UUID,
        runRef: String,
        sourceVersion: String,
    ): DeployBuildNumber? {
        val platform = when (kind) {
            DeployTargetKind.GOOGLE_PLAY -> bosca.workops.model.artifact.AppBuildPlatform.ANDROID
            DeployTargetKind.APP_STORE -> bosca.workops.model.artifact.AppBuildPlatform.IOS
            else -> return null
        }
        val configObject = config as? JsonObject
            ?: error("$kind deploy config must be an object")
        val applicationField = if (platform == bosca.workops.model.artifact.AppBuildPlatform.ANDROID) {
            "packageName"
        } else {
            "bundleId"
        }
        val applicationValue = configObject[applicationField]
            ?: error("$kind deploy config requires $applicationField")
        val applicationId = applicationValue.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("$kind deploy config requires $applicationField")
        val buildKeyValue = configObject["buildNumberKey"]
        val buildKey = if (buildKeyValue == null) {
            "default"
        } else {
            buildKeyValue.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() } ?: "default"
        }
        val refs = listOf(
            "refs/tags/$sourceVersion",
            sourceVersion,
            runRef,
        ).distinct()
        val commit = refs.firstNotNullOfOrNull { repositoryBrowse.resolveRef(repositoryId, it) }
            ?: error("Cannot resolve source commit for '$sourceVersion' in repository $repositoryId")
        val allocation = appBuildNumbers.find(
            repositoryId = repositoryId,
            sourceCommitSha = commit,
            sourceVersion = sourceVersion,
            platform = platform,
            applicationId = applicationId,
            buildKey = buildKey,
        ) ?: error(
            "No durable ${platform.name.lowercase()} build number was allocated for " +
                "$applicationId@$sourceVersion ($commit, key '$buildKey') — add uses: allocate-build-number " +
                "before building the app",
        )
        return DeployBuildNumber(allocation.id, allocation.number, allocation.value)
    }

    private fun interpolate(coordinate: String, versionName: String?): String =
        if (versionName.isNullOrBlank()) {
            coordinate
        } else {
            coordinate
                .replace("\${{ version }}", versionName)
                .replace("\${{ release.version }}", versionName)
        }

    /**
     * The effective adapter config: the deploy.yaml entry's, with step-level overrides on top —
     * coerced to booleans/numbers where they parse, so `phasedRelease: false` reaches a strict
     * Boolean field. `artifact.*` keys route to the selector, not the config.
     */
    private fun mergeConfig(config: JsonElement, overrides: Map<String, String>): JsonElement {
        val base = (config as? JsonObject) ?: JsonObject(emptyMap())
        val effective = overrides.filterKeys { !it.startsWith(ARTIFACT_OVERRIDE_PREFIX) }
        if (effective.isEmpty()) return base
        return JsonObject(
            base + effective.mapValues { (_, value) -> coerce(value) }
        )
    }

    private fun coerce(value: String): JsonPrimitive = when {
        value == "true" || value == "false" -> JsonPrimitive(value.toBoolean())
        value.toLongOrNull() != null -> JsonPrimitive(value.toLong())
        value.toDoubleOrNull() != null -> JsonPrimitive(value.toDouble())
        else -> JsonPrimitive(value)
    }

    private fun healthSeverity(status: bosca.workops.model.environment.HealthCheckStatus): Int = when (status) {
        bosca.workops.model.environment.HealthCheckStatus.HEALTHY -> 0
        bosca.workops.model.environment.HealthCheckStatus.UNKNOWN -> 1
        bosca.workops.model.environment.HealthCheckStatus.DEGRADED -> 2
        bosca.workops.model.environment.HealthCheckStatus.UNHEALTHY -> 3
    }

    companion object {
        const val RELEASE_ID_PARAMETER = "release.id"
        const val RELEASE_VERSION_PARAMETER = "release.version"

        /** [deploymentHealth]'s answer when the environment has no current deployment for the project. */
        const val NO_DEPLOYMENT_HEALTH = "NONE"
        const val ARTIFACT_OVERRIDE_PREFIX = "artifact."
        const val ARTIFACT_COORDINATE_OVERRIDE = "artifact.coordinate"
        const val ARTIFACT_TYPE_OVERRIDE = "artifact.type"
        const val ARTIFACT_NAMESPACE_OVERRIDE = "artifact.namespace"
        private val log = LoggerFactory.getLogger(GitReleaseDeployer::class.java)
        private val json = Json { ignoreUnknownKeys = true }
        private val BETA_APPROVED_STATES = setOf("APPROVED")
        private val BETA_REJECTED_STATES = setOf("REJECTED")
        private val APP_STORE_APPROVED_STATES = setOf(
            "ACCEPTED", "PENDING_APPLE_RELEASE", "PENDING_DEVELOPER_RELEASE",
            "PROCESSING_FOR_DISTRIBUTION", "READY_FOR_DISTRIBUTION", "READY_FOR_SALE",
        )
        private val APP_STORE_REJECTED_STATES = setOf(
            "REJECTED", "DEVELOPER_REJECTED", "METADATA_REJECTED", "INVALID_BINARY",
        )
    }
}
