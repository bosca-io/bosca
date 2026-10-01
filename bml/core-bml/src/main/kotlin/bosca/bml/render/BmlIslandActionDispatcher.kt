package bosca.bml.render

import java.util.Base64
import kotlinx.serialization.json.JsonArray

/**
 * Result of a live-state action: the new client state (null when server-scoped) and, when the
 * state currently owns a view, its re-rendered HTML. Site-scoped state may run without a mounted
 * view, in which case [html] is null and the client performs no DOM update.
 */
data class IslandActionResult(val state: String?, val html: String?)

/**
 * Session slot for page-owned state. Wire keys remain stable dispatcher identities, so the concrete
 * request path participates only in the server-session backing key.
 */
fun bmlPageSessionStateKey(page: String, stateKey: String): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    fun encode(value: String): String = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
    return "page.${encode(page)}.${encode(stateKey)}"
}

/**
 * A live island's server-side action dispatcher (generated, one per live server-binding state key): decodes
 * the state model (from the posted JSON for a `client` scope, or from [RenderContext.session] for a
 * `server` scope), runs the selected `@click`/`@submit` method, persists it (returns new client JSON, or
 * writes back to the session), and re-renders the bound view island's inner content when one exists.
 *
 * Page states register in `bml.generated.BmlIslands.dispatchers` keyed by page route then [stateKey];
 * component states (a server binding inside a `<component>`) register in
 * `bml.generated.BmlIslands.componentDispatchers` under `"<tag>.<binding>"`. Page-lifetime
 * component instances add a `":<key>"` suffix; site-lifetime components keep the exact key.
 */
interface BmlIslandActionDispatcher {
    /** The binding name this dispatcher manages (`counterModel`), or a component state's `"<tag>.<binding>"` prefix. */
    val stateKey: String

    /** True when the state lives in the server session (never sent to the client) rather than a client script. */
    val serverScoped: Boolean

    /** True when the owning component's state identity is shared across pages. */
    val siteScoped: Boolean get() = false

    /**
     * Run [method] on the state model and re-render the view. [state] is the posted client JSON
     * (ignored when [serverScoped]); [args] are the action's posted arguments, positionally matching
     * the non-`ctx` parameters of the `@click`/`@submit` expression; [instanceKey] is the FULL posted
     * state key — a page-lifetime component carries its per-instance suffix, while site-lifetime
     * component state and page-root state equal [stateKey].
     */
    suspend fun dispatch(
        ctx: RenderContext,
        method: String,
        state: String,
        args: JsonArray = JsonArray(emptyList()),
        instanceKey: String = stateKey,
    ): IslandActionResult
}
