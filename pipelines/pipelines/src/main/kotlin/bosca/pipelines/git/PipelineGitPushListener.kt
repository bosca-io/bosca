package bosca.pipelines.git

import bosca.cache.withRequestCache
import bosca.db.withConnectionManager
import bosca.git.model.PushEvent
import bosca.pubsub.PubSubService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Subscribes to the git server's push events and routes each one to
 * [PipelineGitSyncService.onPushEvent]. Symmetric to
 * [bosca.ai.agents.git.AgentGitPushListener]; the sync service itself decides
 * whether a particular push touches PIPELINE_PROJECT paths.
 */
class PipelineGitPushListener(
    private val pubSubService: PubSubService,
    private val pipelineGitSyncService: PipelineGitSyncService,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            while (true) {
                try {
                    pubSubService.subscribe(
                        "bosca.git.push",
                        PushEvent.serializer(),
                    ).collect { msg ->
                        // Establish the request-scoped context (cache + DB connection) the
                        // synchronous sync path needs — mirrors how JobRunner wraps job
                        // execution. Without it, ServiceCache lookups in the browse service
                        // throw "Request cache not found in coroutine context".
                        withRequestCache {
                            withConnectionManager {
                                handle(msg.message)
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Pipeline git push listener failed, retrying in 5s: {}", e.message)
                    delay(5000.milliseconds)
                }
            }
        }
    }

    /**
     * Single-event handler. Exposed at internal visibility so unit tests can drive the
     * routing logic without spinning up the long-running subscription loop. Defensive
     * try/catch wraps the sync call so one bad event can't kill the listener — anything
     * other than [kotlinx.coroutines.CancellationException] is logged and swallowed.
     */
    internal suspend fun handle(event: PushEvent) {
        try {
            pipelineGitSyncService.onPushEvent(event.repositoryId, event.beforeSha, event.afterSha)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(
                "PIPELINE_PROJECT push handling failed for repo {} at {}: {}",
                event.repositoryId, event.afterSha, e.message, e
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PipelineGitPushListener::class.java)
    }
}
