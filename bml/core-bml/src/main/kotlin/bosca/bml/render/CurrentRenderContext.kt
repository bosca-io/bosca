package bosca.bml.render

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

/**
 * Carries the render's [RenderContext] on the coroutine context, so site code arbitrarily deep in
 * the call stack can reach it via [currentRenderContext] instead of threading a `ctx` parameter
 * through every function.
 *
 * `bml-server` installs it around every entry point that runs generated BML code — page renders
 * (incl. not-found/error pages), sliver re-renders, and live-island action dispatch — so both
 * `<script server>` bodies and the site Kotlin they call see it. Anything invoking a renderer
 * directly (tests, custom hosts) wraps with [withRenderContext].
 */
public class RenderContextElement(public val context: RenderContext) : AbstractCoroutineContextElement(Key) {
    public companion object Key : CoroutineContext.Key<RenderContextElement>
}

/**
 * The [RenderContext] of the render (or live-island action) this coroutine is executing — the same
 * instance the generated code received as `ctx`. Fails fast when called outside a render, naming
 * the fix.
 */
public suspend fun currentRenderContext(): RenderContext =
    currentCoroutineContext()[RenderContextElement]?.context ?: error(
        "No RenderContext in the coroutine context. currentRenderContext() works inside BML render " +
            "and action code (bml-server installs it); for direct render calls, wrap with " +
            "withRenderContext(ctx) { … }.",
    )

/**
 * The ambient render's GraphQL data-plane client — shorthand for `currentRenderContext().gql`.
 * Never null; see [RenderContext.gql].
 */
public suspend fun client(): bosca.bml.graphql.GraphQLClient = currentRenderContext().gql

/**
 * The ambient render's locale — shorthand for `currentRenderContext().locale`. See
 * [RenderContext.locale] for how the server resolves it per request; for the
 * BCP-47 tag string markup wants, use [RenderContext.lang].
 */
public suspend fun currentLocale(): java.util.Locale = currentRenderContext().locale

/**
 * Runs [block] with [context] installed as the coroutine's [RenderContext]. Re-installing the
 * SAME instance is a no-op passthrough (nested render layers — a page rendering its components —
 * don't pay a context switch per layer).
 */
public suspend fun <T> withRenderContext(context: RenderContext, block: suspend () -> T): T =
    if (currentCoroutineContext()[RenderContextElement]?.context === context) {
        block()
    } else {
        withContext(RenderContextElement(context)) { block() }
    }
