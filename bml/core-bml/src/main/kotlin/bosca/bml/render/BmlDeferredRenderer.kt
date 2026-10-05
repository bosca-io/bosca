package bosca.bml.render

/**
 * A compiler-generated server renderer for one `render="deferred"` island declaration.
 *
 * The initial page contains only the island boundary, its public serialized [props], and its
 * fallback. The browser later posts those props to BML Server, which creates a fresh authenticated
 * [RenderContext] and invokes [render]. Implementations must not capture the enclosing page or
 * component render: every value crossing the request boundary is an explicit prop.
 */
interface BmlDeferredRenderer {
    /** Stable, compiler-namespaced wire identifier used by `POST /_bml/deferred/{id}`. */
    val id: String

    /** Page route that owns this renderer, or null when it is declared by a reusable component. */
    val pageRoute: String? get() = null

    /** Component tag that owns this renderer, or null when it is declared directly by a page. */
    val ownerComponentTag: String? get() = null

    /** Components the deferred body can render; used for session and asset closure checks. */
    val componentTags: List<String> get() = emptyList()

    /** True when the deferred body itself owns server-session live state. */
    val hasServerState: Boolean get() = false

    /** Live-state dispatchers owned by this deferred render scope. */
    val islandActionDispatchers: List<BmlIslandActionDispatcher> get() = emptyList()

    /** Render only the deferred island body into [RenderContext.writer]. */
    suspend fun render(ctx: RenderContext, props: Map<String, Any?>)
}
