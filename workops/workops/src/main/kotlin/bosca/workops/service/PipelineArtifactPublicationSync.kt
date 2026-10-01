package bosca.workops.service

import bosca.cache.withRequestCache
import bosca.db.withConnectionManager
import bosca.git.model.PipelineEvent
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.service.PipelineRunService
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.workops.model.artifact.PublicationStatus
import bosca.workops.model.artifact.RegisterArtifactInput
import bosca.workops.model.artifact.artifactTypeOfString
import bosca.workops.model.version.Version
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Registers the declared artifacts of a successful git-ci release/tag run as WorkOps publications.
 * The run's repository link selects the project; `release.id` selects its exact bundled version, with
 * `release.version` or a tag name as the fallback for member tag pipelines. Registration is idempotent,
 * so an at-least-once pipeline event cannot create duplicate publications.
 */
class PipelineArtifactPublicationSync(
    private val runService: PipelineRunService,
    private val projectRepositories: ProjectRepositoryService,
    private val releaseService: ReleaseService,
    private val versionService: VersionService,
    private val publicationService: ArtifactPublicationService,
) {

    suspend fun apply(event: PipelineEvent) {
        if (event.status != PipelineRunStatus.SUCCESS) return
        val run = runService.findById(event.pipelineRunId) ?: return
        val artifacts = runService.artifacts(run.id)
        if (artifacts.isEmpty()) return

        val linkedProjects = projectRepositories.listByRepository(run.repositoryId)
        if (linkedProjects.isEmpty()) {
            log.debug("Successful run {} has declared artifacts but its repository is not linked to WorkOps", run.id)
            return
        }
        val releaseVersions = releaseVersions(run)
        val candidates = linkedProjects.mapNotNull { link ->
            resolveVersion(run, link.projectId, releaseVersions)?.let { link.projectId to it }
        }.distinctBy { it.first to it.second.id }
        if (candidates.isEmpty()) {
            log.warn("Successful run {} declared artifacts but no linked WorkOps version matches {}", run.id, run.ref)
            return
        }
        check(candidates.size == 1) {
            "Run ${run.id} maps to several WorkOps project versions; repository ownership is ambiguous"
        }

        val (projectId, version) = candidates.single()
        for (artifact in artifacts) {
            val current = publicationService.listByVersion(version.id)
                .firstOrNull { it.coordinates == artifact.coordinate }
                ?: publicationService.register(
                    RegisterArtifactInput(
                        versionId = version.id,
                        projectId = projectId,
                        artifactType = artifactTypeOfString(artifact.type),
                        coordinates = artifact.coordinate,
                        namespace = artifact.namespace,
                        environments = artifact.environments,
                    )
                )
            if (current.status == PublicationStatus.PENDING) {
                val actorId = run.triggeredBy
                if (actorId == null) {
                    log.warn("Publication {} was registered from run {} without an initiating principal", current.id, run.id)
                } else {
                    publicationService.markPublished(current.id, actorId, current.version)
                }
            }
        }
    }

    private suspend fun releaseVersions(run: PipelineRun): Map<UUID, UUID> {
        val releaseId = parameter(run, RELEASE_ID_PARAMETER)
            ?.let { runCatching { UUID.parse(it) }.getOrNull() }
            ?: return emptyMap()
        return releaseService.listVersions(releaseId).associate { it.projectId to it.versionId }
    }

    private suspend fun resolveVersion(
        run: PipelineRun,
        projectId: UUID,
        releaseVersions: Map<UUID, UUID>,
    ): Version? {
        releaseVersions[projectId]?.let { return versionService.getById(it) }
        val name = parameter(run, RELEASE_VERSION_PARAMETER)
            ?: run.ref.takeIf { it.startsWith(TAG_REF_PREFIX) }?.removePrefix(TAG_REF_PREFIX)
            ?: return null
        return versionService.listByProject(projectId).firstOrNull {
            it.name == name || it.name.removePrefix("v") == name.removePrefix("v")
        }
    }

    private fun parameter(run: PipelineRun, name: String): String? =
        (run.parameters as? JsonObject)?.get(name)?.jsonPrimitive?.contentOrNull

    private companion object {
        const val RELEASE_ID_PARAMETER = "release.id"
        const val RELEASE_VERSION_PARAMETER = "release.version"
        const val TAG_REF_PREFIX = "refs/tags/"
        val log = LoggerFactory.getLogger(PipelineArtifactPublicationSync::class.java)
    }
}

/** Long-running subscription for [PipelineArtifactPublicationSync]. */
class PipelineArtifactPublicationListener(
    pubSubService: PubSubService,
    private val sync: PipelineArtifactPublicationSync,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            while (true) {
                try {
                    pubSubService.subscribe(PIPELINE_STATUS_CHANNEL, PipelineEvent.serializer()).collect { message ->
                        withRequestCache {
                            withConnectionManager {
                                sync.apply(message.message)
                            }
                        }
                    }
                    log.warn("Pipeline artifact publication subscription completed; resubscribing in 5s")
                    delay(5000.milliseconds)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Pipeline artifact publication listener failed, retrying in 5s: {}", e.message, e)
                    delay(5000.milliseconds)
                }
            }
        }
    }

    private companion object {
        const val PIPELINE_STATUS_CHANNEL = "bosca.git.pipeline"
        val log = LoggerFactory.getLogger(PipelineArtifactPublicationListener::class.java)
    }
}
