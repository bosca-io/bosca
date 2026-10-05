package bosca.analytics.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element that carries ambient analytics context
 * (application, installation, session, job id, workflow id) for code paths that have no
 * [bosca.server.ServerCall] — typically background job executors,
 * workers, schedulers, and agents.
 *
 * The element is read by [ServerAnalyticsClient.captureException] and
 * the [AnalyticsMiddleware] when building error events. Nested
 * [withAnalyticsContext] calls compose: inner entries override outer
 * entries with the same key, but other outer entries are preserved.
 *
 * Precedence (lowest → highest), as documented in
 * [AnalyticsErrorContextResolver]:
 * 1. Middleware-collected request context
 * 2. `call.analyticsContext` typed context
 * 3. [AnalyticsContextElement] (this)
 * 4. Explicit `context:` argument to `captureException`
 */
class AnalyticsContextElement internal constructor(
    private val context: () -> AnalyticsContext,
) : CoroutineContext.Element {

    /** Creates a scope with a fixed analytics context. */
    constructor(entries: AnalyticsContext) : this({ entries })

    /** Current identity, resolved on access for request scopes that authenticate after entry. */
    val entries: AnalyticsContext get() = context()

    /** Creates a typed context from analytics payload keys and additional entries. */
    constructor(entries: Map<String, Any?>) : this(AnalyticsContext.fromEntries(entries))

    override val key: CoroutineContext.Key<*> get() = Key

    /**
     * Returns the merge of this element with [other], with [other]
     * keys winning on collision. Used by [withAnalyticsContext] to
     * compose nested scopes.
     */
    fun mergedWith(other: Map<String, Any?>): AnalyticsContextElement =
        AnalyticsContextElement { AnalyticsContext.fromEntries(entries.toMap() + other) }

    companion object Key : CoroutineContext.Key<AnalyticsContextElement> {

        /**
         * Returns the typed analytics context on
         * the calling coroutine, or an empty context when none is present.
         * Useful for non-suspend code that has access to the
         * [CoroutineScope].
         */
        fun get(scope: CoroutineScope): AnalyticsContext {
            val element = scope.coroutineContext[Key]
            return if (element == null) AnalyticsContext() else element.entries
        }
    }
}

/**
 * Returns the analytics context entries from the current coroutine
 * context, or an empty context. Suspend variant of
 * [AnalyticsContextElement.Key.get].
 */
suspend fun analyticsContext(): AnalyticsContext {
    val element = currentCoroutineContext()[AnalyticsContextElement.Key]
    return if (element == null) AnalyticsContext() else element.entries
}

/** Runs [block] with typed analytics identity; provided fields override the enclosing context. */
suspend fun <T> withAnalyticsContext(
    context: AnalyticsContext,
    block: suspend () -> T,
): T = withAnalyticsContextEntries(context.toMap(), block)

/**
 * Runs [block] inside a coroutine scope that has the given analytics
 * context [entries] attached. Nested calls compose: the inner scope
 * sees the union of all enclosing entries, with inner keys overriding
 * outer ones on collision.
 *
 * Example:
 * ```
 * withAnalyticsContext("jobId" to job.id, "queue" to "content-index") {
 *     processJob(job)
 *     // any captureException inside this block automatically gets the
 *     // jobId and queue attached.
 * }
 * ```
 */
suspend inline fun <T> withAnalyticsContext(
    vararg entries: Pair<String, Any?>,
    crossinline block: suspend () -> T,
): T = withAnalyticsContextEntries(entries.toMap()) { block() }

@PublishedApi
internal suspend fun <T> withAnalyticsContextEntries(
    entries: Map<String, Any?>,
    block: suspend () -> T,
): T {
    val current = currentCoroutineContext()[AnalyticsContextElement.Key]
    val merged = if (current == null) AnalyticsContextElement(entries) else current.mergedWith(entries)
    return withContext(merged) { block() }
}
