package bosca.bml.message.server

import bosca.bml.message.BmlMessageContext
import bosca.bml.message.host.BmlMessageHostException
import bosca.bml.message.host.BmlMessageProjectHost
import bosca.bml.message.RenderedMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The hosted-project registry: one [BmlMessageProjectHost] per message project, activated at the
 * latest published version and hot-swapped on new publishes. Renders are lock-free against the
 * active generation (the host drains retired ones); a failed activation keeps the last good
 * version serving (fail closed, log-and-continue).
 */
class MessageProjects(
    private val cache: MessageJarCache,
    private val parent: ClassLoader,
) {
    private val hosts = ConcurrentHashMap<String, BmlMessageProjectHost>()

    // Version-pinned hosts (registry pin/rollback renders a specific published version). Keyed
    // "project@version", created lazily on the first pinned render and kept for the pin's
    // lifetime — distinct pinned versions are few, and jars are immutable so never re-swapped.
    private val pinned = ConcurrentHashMap<String, BmlMessageProjectHost>()
    private val pinnedCreation = Mutex()

    val projects: Set<String> get() = hosts.keys

    /** The active version of [project], or null when it is unknown / never activated. */
    fun activeVersion(project: String): String? = hosts[project]?.activeVersion

    /** The active template keys of [project] (diagnostics). */
    fun templateKeys(project: String): Set<String> = hosts[project]?.templateKeys ?: emptySet()

    /** Whether [templateKey] on [project]'s active version declares companion push output. */
    fun supportsPush(project: String, templateKey: String): Boolean =
        hosts[project]?.supportsPush(templateKey) == true

    /** Whether [templateKey] on [project]'s active version declares email output. */
    fun supportsEmail(project: String, templateKey: String): Boolean =
        hosts[project]?.supportsEmail(templateKey) == true

    /** The sample-payload skeleton of [templateKey] on [project]'s active version, when declared. */
    fun payloadSample(project: String, templateKey: String): kotlinx.serialization.json.JsonElement? =
        hosts[project]?.payloadSample(templateKey)

    /** The payload JSON-Schema of [templateKey] on [project]'s active version, when declared. */
    fun payloadSchema(project: String, templateKey: String): kotlinx.serialization.json.JsonElement? =
        hosts[project]?.payloadSchema(templateKey)

    /**
     * Activate `(project, version)`: fetch (cached, digest-verified) and atomically swap it in.
     * The new jar loads and validates BEFORE displacing the running one. No-op when the version
     * is already active.
     */
    suspend fun activate(project: String, version: String) {
        val host = hosts.computeIfAbsent(project) { BmlMessageProjectHost(parent) }
        if (host.activeVersion == version) return
        val jar = cache.jarFor(project, version)
        host.swap(jar, version)
        log.info("bml-message: activated {}@{} — templates: {}", project, version, host.templateKeys.sorted())
    }

    /**
     * Render [templateKey] of [project] — on its active version, or on an explicitly requested
     * published [version] (the registry's pin/rollback path). Fails closed on unknowns.
     */
    /** Render all channels declared by one BML message unit. */
    suspend fun render(
        project: String,
        templateKey: String,
        message: BmlMessageContext,
        version: String? = null,
    ): RenderedMessage {
        val host = hosts[project] ?: throw BmlMessageHostException("Unknown message project '$project'")
        if (version == null || version == host.activeVersion) return host.render(templateKey, message)
        return pinnedHost(project, version).render(templateKey, message)
    }

    private suspend fun pinnedHost(project: String, version: String): BmlMessageProjectHost {
        val key = "$project@$version"
        pinned[key]?.let { return it }
        pinnedCreation.withLock {
            pinned[key]?.let { return it }
            val jar = try {
                cache.jarFor(project, version)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A version the registry doesn't have is a caller error, not a server fault.
                throw BmlMessageHostException("message project '$project' version '$version' is not available: ${e.message}")
            }
            val host = BmlMessageProjectHost(parent)
            host.swap(jar, version)
            log.info("bml-message: hosting pinned {}@{} — templates: {}", project, version, host.templateKeys.sorted())
            pinned[key] = host
            return host
        }
    }

    fun close() {
        hosts.values.forEach { runCatching { it.close() } }
        pinned.values.forEach { runCatching { it.close() } }
    }

    private companion object {
        val log = LoggerFactory.getLogger(MessageProjects::class.java)
    }
}
