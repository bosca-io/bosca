package bosca.bml.message.server

import bosca.artifacts.model.ArtifactVersionPublished
import bosca.bml.message.BmlMessageArtifacts
import bosca.pubsub.PubSubService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * The author-and-go loop's server half: keep every registered project on its
 * latest published version.
 *
 * - **Warmup**: DISCOVER every project published under the bml-message namespace (registry
 *   listing), configured seeds, and bundled fallbacks. Prefer the latest registry version, but
 *   activate a bundled version for a project that a fresh process cannot fetch.
 *   Discovery is what makes restarts self-healing: a server that missed publish events while
 *   down still finds everything.
 * - **Event**: subscribe to the registry's `bosca.artifacts.version.published` and react to the
 *   `bml-message` namespace — this is also how NEW projects (not in the seed list) auto-register.
 * - **Poll backstop**: the event is best-effort; a periodic latest-version check guarantees a
 *   missed event can't strand a stale jar.
 */
class MessageReloader(
    private val projects: MessageProjects,
    private val client: MessageArtifactClient,
    private val seeds: List<String>,
    private val pollInterval: Duration,
    private val pubSub: PubSubService?,
    private val bundled: BundledMessageProjects = BundledMessageProjects.Empty,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True once [warmup] finished (readiness) — per-project failures log and retry via poll. */
    @Volatile
    var warmedUp: Boolean = false
        private set

    suspend fun warmup() {
        for (project in discover()) {
            try {
                activateLatest(project)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("bml-message: warmup failed for {} — will retry on poll: {}", project, e.message)
            }
        }
        warmedUp = true
    }

    /**
     * The union of registry-published projects, seeds, and everything already hosted. The
     * registry listing is the authority; its failure logs and degrades to seeds + known so a
     * registry outage never empties the serving set.
     */
    private suspend fun discover(): Set<String> {
        val published = try {
            client.projects()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("bml-message: project discovery failed — continuing with seeds + known: {}", e.message)
            emptyList()
        }
        return (published + seeds + bundled.projects + projects.projects).toSet()
    }

    fun start() {
        if (pubSub != null) {
            scope.launch {
                while (true) {
                    try {
                        pubSub.subscribe(ArtifactVersionPublished.CHANNEL, ArtifactVersionPublished.serializer())
                            .collect { message ->
                                val event = message.message
                                if (event.namespace == BmlMessageArtifacts.NAMESPACE) {
                                    log.info("bml-message: published event {}@{}", event.repository, event.version)
                                    try {
                                        projects.activate(event.repository, event.version)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        log.error("bml-message: activation failed for {}@{}: {}", event.repository, event.version, e.message)
                                    }
                                }
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error("bml-message: publish listener failed, retrying in 5s: {}", e.message)
                        delay(5_000)
                    }
                }
            }
        }
        scope.launch {
            while (true) {
                delay(pollInterval)
                // Re-discover every poll: newly published projects appear even when the
                // publish event was missed (or NATS is not configured at all).
                for (project in discover()) {
                    try {
                        activateLatest(project)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("bml-message: poll check failed for {}: {}", project, e.message)
                    }
                }
            }
        }
    }

    private suspend fun activateLatest(project: String) {
        val latest = try {
            client.latestVersion(project)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (activateBundledIfUnhosted(project, "registry lookup failed: ${e.message}")) return
            throw e
        }
        if (latest != null) {
            try {
                projects.activate(project, latest.version)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (activateBundledIfUnhosted(project, "registry activation failed: ${e.message}")) return
                throw e
            }
        }
        if (activateBundledIfUnhosted(project, "no published version with a jar")) return
        if (projects.activeVersion(project) == null) {
            log.warn("bml-message: {} has no published or bundled version with a jar", project)
        }
    }

    private suspend fun activateBundledIfUnhosted(project: String, reason: String): Boolean {
        // A registry outage must never roll an already hosted registry version back to the older
        // image fallback. The active generation stays valid and the next poll retries the lookup.
        if (projects.activeVersion(project) != null) return false
        val fallback = bundled.latest(project) ?: return false
        return try {
            projects.activate(project, fallback.version)
            log.warn(
                "bml-message: activated bundled fallback {}@{} ({})",
                project,
                fallback.version,
                reason,
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Bundled fallback activation failed after $reason", e)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(MessageReloader::class.java)
    }
}
