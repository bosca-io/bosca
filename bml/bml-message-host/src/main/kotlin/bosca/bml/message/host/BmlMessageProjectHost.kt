package bosca.bml.message.host

import bosca.bml.message.BmlMessageContext
import bosca.bml.message.RenderedMessage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * One message project's active jar with hot-swap semantics: renders run
 * lock-free against the active [BmlMessageJar]; [swap] atomically replaces it, and the retired
 * jar's classloader closes only after its last in-flight render finishes — no render is ever
 * interrupted by a reload, and no classloader lingers once drained.
 */
class BmlMessageProjectHost(
    private val parent: ClassLoader,
) : AutoCloseable {

    /**
     * The active jar plus its drain state. `retired` flips exactly once (by whoever unseats it);
     * the in-flight count decides who closes the loader — the swapper when the jar is idle, else
     * the last render out. `closeOnce` keeps those paths from racing a double close.
     */
    private class Active(val jar: BmlMessageJar, val version: String) {
        val inFlight = AtomicInteger(0)
        @Volatile
        var retired = false
        private val closeOnce = AtomicBoolean(false)

        fun closeIfDrained() {
            if (retired && inFlight.get() == 0 && closeOnce.compareAndSet(false, true)) {
                jar.close()
            }
        }
    }

    private val active = AtomicReference<Active?>(null)

    /** The active version, or null before the first [swap]. */
    val activeVersion: String? get() = active.get()?.version

    /** The active jar's template keys (for registry/diagnostics), empty before the first [swap]. */
    val templateKeys: Set<String> get() = active.get()?.jar?.templates?.keys ?: emptySet()

    /** Whether [templateKey] on the active version declares companion push output. */
    fun supportsPush(templateKey: String): Boolean =
        active.get()?.jar?.templates?.get(templateKey)?.supportsPush == true

    /** Whether [templateKey] on the active version declares email output. */
    fun supportsEmail(templateKey: String): Boolean =
        active.get()?.jar?.templates?.get(templateKey)?.supportsEmail == true

    /**
     * The sample-payload skeleton for [templateKey] on the active version, derived from the
     * template's declared payload serializer ([bosca.bml.message.PayloadSample]); null when the
     * template is unknown, takes no payload, or didn't declare one recognizably.
     */
    fun payloadSample(templateKey: String): kotlinx.serialization.json.JsonElement? {
        val template = active.get()?.jar?.templates?.get(templateKey) ?: return null
        val serializer = template.payloadSerializer ?: return null
        return bosca.bml.message.PayloadSample.of(serializer.descriptor)
    }

    /**
     * The JSON-Schema subset for [templateKey]'s payload on the active version, derived from the
     * template's declared payload serializer ([bosca.bml.message.PayloadSchema]); null when the
     * template is unknown, takes no payload, or didn't declare one recognizably.
     */
    fun payloadSchema(templateKey: String): kotlinx.serialization.json.JsonElement? {
        val template = active.get()?.jar?.templates?.get(templateKey) ?: return null
        val serializer = template.payloadSerializer ?: return null
        return bosca.bml.message.PayloadSchema.of(serializer.descriptor)
    }

    /** Test seam: the active generation's jar — leak tests weak-reference its real classes/loader. */
    internal val activeJarForTest: BmlMessageJar? get() = active.get()?.jar

    /**
     * Render [templateKey] against [message] on the active version. Fails closed
     * ([BmlMessageHostException]) when no version is loaded or the key is unknown.
     */
    suspend fun render(templateKey: String, message: BmlMessageContext): RenderedMessage {
        while (true) {
            val current = active.get()
                ?: throw BmlMessageHostException("No message jar version is loaded for this project")
            current.inFlight.incrementAndGet()
            if (current.retired) {
                // Lost the race with a swap between the read and the increment: back out (closing
                // if we were the drain's last holdout) and retry against the new active.
                current.inFlight.decrementAndGet()
                current.closeIfDrained()
                continue
            }
            try {
                return current.jar.render(templateKey, message)
            } finally {
                current.inFlight.decrementAndGet()
                current.closeIfDrained()
            }
        }
    }

    /**
     * Atomically activate [jar] as [version]: the new jar loads (and validates) BEFORE the switch,
     * so a bad artifact never displaces a working one — the last good version keeps serving. The
     * previous jar retires and closes once its in-flight renders drain.
     */
    fun swap(jar: File, version: String) {
        val next = Active(BmlMessageJar.load(jar, parent), version)
        retire(active.getAndSet(next))
    }

    /** Retire and drain the active jar (host shutdown). */
    override fun close() {
        retire(active.getAndSet(null))
    }

    private fun retire(previous: Active?) {
        previous?.let {
            it.retired = true
            it.closeIfDrained()
        }
    }
}
