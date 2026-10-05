package bosca.git.ci.configuration

import bosca.di.provide
import bosca.git.ci.service.PipelineRequirementChecker
import bosca.git.ci.service.KubernetesCiDispatcher
import bosca.git.model.PipelineEvent
import bosca.git.model.PipelineRunStatus
import bosca.cache.withRequestCache
import bosca.db.withConnectionManager
import bosca.pubsub.PubSubService
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Local copy of the registry's `bosca.artifacts.version.published` payload — the fields are the
 * wire contract (the platform's loose-coupling convention for cross-service events), so git-ci
 * doesn't take a dependency on the artifacts module just to hear about publishes.
 */
@Serializable
private data class ArtifactVersionPublished(
    val namespace: String = "",
    val repository: String = "",
    val type: String = "",
    val version: String = "",
)

/**
 * Boot-time wiring for git-ci's requirement gate: subscribes to the two events that can satisfy a
 * waiting requirement and re-evaluates every queued gated job on each —
 *
 *  - the artifact registry's version-published announcements, the common path that
 *    releases an artifact waiter within seconds of its provider publishing;
 *  - the pipeline status channel: a run reaching a terminal status releases pipeline
 *    waiters immediately — SUCCESS satisfies them, FAILURE/CANCELLED fails them fast instead of
 *    letting them wait out a deadline on a dependency that already lost.
 *
 * The scheduled sweep (`pipeline-requirement-check`) is the backstop for missed events and the
 * deadline enforcer.
 */
class GitCiModule : BoscaApplicationModule {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun install(application: BoscaApplication) {
        val pubSubService = provide<PubSubService>()
        subscribeForever("bosca.artifacts.version.published", ArtifactVersionPublished.serializer(), pubSubService) { msg ->
            log.debug(
                "Artifact published ({}/{} {} {}) — re-evaluating requirement-gated jobs",
                msg.namespace, msg.repository, msg.type, msg.version,
            )
            true
        }
        subscribeForever(PIPELINE_STATUS_CHANNEL, PipelineEvent.serializer(), pubSubService) { event ->
            val terminal = event.status == PipelineRunStatus.SUCCESS ||
                event.status == PipelineRunStatus.FAILURE ||
                event.status == PipelineRunStatus.CANCELLED
            if (terminal) {
                log.debug(
                    "Pipeline run {} reached {} — re-evaluating requirement-gated jobs",
                    event.pipelineRunId, event.status,
                )
            }
            terminal
        }
        val kubernetesDispatch = provide<KubernetesCiDispatchConfiguration>()
        val kubernetesDispatcher = provide<KubernetesCiDispatcher>()
        if (kubernetesDispatcher.enabled) {
            log.info(
                "Kubernetes CI dispatch enabled for runner profiles: {}",
                kubernetesDispatch.profiles.sorted().joinToString(),
            )
            scope.launch {
                while (true) {
                    try {
                        withRequestCache {
                            withConnectionManager {
                                kubernetesDispatcher.reconcileExecutions()
                                kubernetesDispatcher.dispatchAvailable()
                            }
                        }
                        delay(KUBERNETES_DISPATCH_INTERVAL)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error("Kubernetes CI dispatch sweep failed, retrying: {}", e.message, e)
                        delay(KUBERNETES_DISPATCH_INTERVAL)
                    }
                }
            }
        }
    }

    /**
     * Subscribes to [channel] and re-runs the requirement checker for every message [accept]s,
     * retrying the subscription forever.
     */
    private fun <T : Any> subscribeForever(
        channel: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        pubSubService: PubSubService,
        accept: (T) -> Boolean,
    ) {
        scope.launch {
            while (true) {
                try {
                    pubSubService.subscribe(channel, serializer).collect { msg ->
                        if (!accept(msg.message)) return@collect
                        // PubSub collectors run on a bare scope — establish the request-scoped
                        // context (cache + DB connection) the checker's repositories need, exactly
                        // like JobRunner does for jobs. Without it the first lookup throws and the
                        // event is silently lost (the NotificationDispatcher lesson).
                        withRequestCache {
                            withConnectionManager {
                                provide<PipelineRequirementChecker>().checkAwaiting()
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("{} listener failed, retrying in 5s: {}", channel, e.message)
                    delay(5000.milliseconds)
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(GitCiModule::class.java)

        /** [PipelineEvent]'s pubsub channel (its `@JobEvent(pubsubChannel = ...)` declaration). */
        private const val PIPELINE_STATUS_CHANNEL = "bosca.git.pipeline"
        private val KUBERNETES_DISPATCH_INTERVAL = 5_000.milliseconds
    }
}
