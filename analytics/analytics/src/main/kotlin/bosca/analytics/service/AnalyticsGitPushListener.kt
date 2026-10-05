package bosca.analytics.service

import bosca.analytics.jobs.AnalyticsQuerySyncJob
import bosca.analytics.jobs.enqueue
import bosca.git.model.PushEvent
import bosca.pubsub.PubSubService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Subscribes to the git server's push events and enqueues an
 * [AnalyticsQuerySyncJob] for each one. The actual SQL writeback runs in
 * the JobQueue under a distributed lock, so this listener stays cheap and
 * the heavy work is deduplicated by the queue regardless of how many
 * processes subscribe to PubSub.
 */
class AnalyticsGitPushListener(
    private val pubSubService: PubSubService,
) {

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Default)

    init {
        scope.launch {
            while (true) {
                try {
                    pubSubService.subscribe(
                        "bosca.git.push",
                        PushEvent.serializer(),
                    ).collect { msg ->
                        handle(msg.message)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Analytics git push listener failed, retrying in 5s: {}", e.message)
                    delay(5000.milliseconds)
                }
            }
        }
    }

    internal suspend fun handle(event: PushEvent) {
        AnalyticsQuerySyncJob(
            repositoryId = event.repositoryId,
            ref = event.ref,
            beforeSha = event.beforeSha,
            afterSha = event.afterSha,
        ).enqueue()
    }

    suspend fun shutdown() {
        scopeJob.cancelAndJoin()
    }

    companion object {
        private val log = LoggerFactory.getLogger(AnalyticsGitPushListener::class.java)
    }
}
