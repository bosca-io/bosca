package bosca.scripting.service

import bosca.git.model.PushEvent
import bosca.pubsub.PubSubService
import bosca.scripting.jobs.ScriptSourceSyncJob
import bosca.scripting.jobs.enqueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Subscribes to the git server's push events and enqueues a
 * [ScriptSourceSyncJob] for each one. Symmetric to
 * [bosca.analytics.service.AnalyticsGitPushListener] for the scripting
 * module; actual writeback runs in the JobQueue.
 */
class ScriptingGitPushListener(
    private val pubSubService: PubSubService,
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
                        val event = msg.message
                        ScriptSourceSyncJob(
                            repositoryId = event.repositoryId,
                            ref = event.ref,
                            beforeSha = event.beforeSha,
                            afterSha = event.afterSha,
                        ).enqueue()
                    }
                } catch (e: Exception) {
                    log.error("Scripting git push listener failed, retrying in 5s: {}", e.message)
                    delay(5000.milliseconds)
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ScriptingGitPushListener::class.java)
    }
}
