package bosca.workops.service

import bosca.cache.withRequestCache
import bosca.db.withConnectionManager
import bosca.git.model.CommitStatusState
import bosca.git.model.PushEvent
import bosca.git.service.CommitStatusService
import bosca.git.service.RepositoryWriteService
import bosca.pubsub.PubSubService
import bosca.workops.deploy.DeployConfigService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Validates `.bosca/deploy.yaml` the moment a push changes it: parse errors, unknown
 * target kinds, configs the target adapter cannot decode, and environment stanzas no linked program
 * declares surface as a `deploy-config` commit status on the pushed SHA — not three steps into a
 * release, which is where the 2026-07 promote failure's config mislabel first showed itself.
 */
class DeployConfigPushValidator(
    private val deployConfigService: DeployConfigService,
    private val repositoryWrite: RepositoryWriteService,
    private val commitStatusService: CommitStatusService,
) {

    suspend fun handle(event: PushEvent) {
        try {
            val content = repositoryWrite.readFile(
                event.repositoryId, event.afterSha, DeployConfigService.DEPLOY_CONFIG_PATH,
            ) ?: return
            // Only a push that CHANGED the file re-validates — every other push stays silent.
            if (event.beforeSha.any { it != '0' }) {
                val before = repositoryWrite.readFile(
                    event.repositoryId, event.beforeSha, DeployConfigService.DEPLOY_CONFIG_PATH,
                )
                if (content == before) return
            }
            val problems = deployConfigService.validate(event.repositoryId, content)
            commitStatusService.recordStatus(
                repositoryId = event.repositoryId,
                commitSha = event.afterSha,
                context = STATUS_CONTEXT,
                state = if (problems.isEmpty()) CommitStatusState.SUCCESS else CommitStatusState.FAILURE,
                description = if (problems.isEmpty()) {
                    "deploy.yaml is valid"
                } else {
                    problems.joinToString("; ").take(MAX_DESCRIPTION_LENGTH)
                },
            )
            if (problems.isNotEmpty()) {
                log.warn(
                    "deploy.yaml at {} in {} failed validation: {}",
                    event.afterSha, event.repositoryId, problems.joinToString("; "),
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // One bad event must not kill the listener; the failure is loud in the log and the
            // absence of a deploy-config status on the commit is itself visible.
            log.error("Deploy config validation errored for push to {}: {}", event.repositoryId, e.message, e)
        }
    }

    companion object {
        const val STATUS_CONTEXT = "deploy-config"
        private const val MAX_DESCRIPTION_LENGTH = 500
        private val log = LoggerFactory.getLogger(DeployConfigPushValidator::class.java)
    }
}

/**
 * The long-running subscription wrapping [DeployConfigPushValidator]: each `bosca.git.push` event
 * is handled inside the request-scoped context (cache + DB connection) a raw subscription lacks.
 * Only the composition root constructs this — tests drive the validator directly, because
 * instantiating the loop against a mock PubSub would busy-spin the JVM.
 */
class DeployConfigPushListener(
    private val pubSubService: PubSubService,
    private val validator: DeployConfigPushValidator,
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
                        withRequestCache {
                            withConnectionManager {
                                validator.handle(msg.message)
                            }
                        }
                    }
                    // The flow completing (broker reconnect, shutdown race) must not become a hot
                    // resubscribe loop.
                    log.warn("Deploy config push subscription completed; resubscribing in 5s")
                    delay(5000.milliseconds)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Deploy config push listener failed, retrying in 5s: {}", e.message)
                    delay(5000.milliseconds)
                }
            }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(DeployConfigPushListener::class.java)
    }
}
