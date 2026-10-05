package bosca.bml.render

/**
 * A compiled BML page: its route [route] pattern plus its [render] function. The compiler plugin
 * makes each generated page `object` implement this, and `bml-server` registers them **directly on
 * Bosca Server's router** (`router.get(route)`) — there is no separate BML routing layer. Bosca's
 * router does path matching (incl. `{param}`); the server fills [RenderContext.params] + token from
 * the matched call before invoking [render].
 */
interface BmlPageRenderer {
    /**
     * The route pattern, e.g. `/lists/{id}`. It comes from `<page route="…">` or
     * `<route path="…">`; Bosca's router matches it and extracts the params.
     */
    val route: String

    /**
     * Stable digest of the BML source and compiler revision that produced this renderer. BML includes
     * this in its deployment revision so shared-shell validators and immutable asset URLs change when
     * the compiled page changes.
     */
    val renderRevision: String get() = ""

    /**
     * The HTTP media type returned by this page. HTML is the compatible default; the BML server
     * only injects stylesheets, client scripts, and development reload markup into HTML pages.
     */
    val contentType: String get() = "text/html"

    /** The page's bundled client JS filename (e.g. `HomePage.js`) when it has `<script client>`/islands, else null. */
    val clientModule: String? get() = null

    /**
     * The component tags this page renders directly. The asset pipeline expands this to the transitive
     * closure (over each component's deps) to decide which per-component CSS/JS chunks to link for this
     * page — "included based on what's rendered". Empty when the page uses no components.
     */
    val componentTags: List<String> get() = emptyList()

    /**
     * Components rendered during the initial page request. This excludes components that occur
     * only under deferred islands, while [componentTags] continues to include them for CSS/JS
     * asset planning. BML Server uses this narrower closure for work performed by the shell itself,
     * such as request-identity feature flag validation.
     */
    val eagerComponentTags: List<String> get() = componentTags

    /** Deferred island renderers declared directly by this page. */
    val deferredRenderers: List<BmlDeferredRenderer> get() = emptyList()

    /** The live-island action dispatchers this page declares (generated). Empty unless it has `@click` server actions. */
    val islandActionDispatchers: List<BmlIslandActionDispatcher> get() = emptyList()

    /** True when the page has any `scope="server-session"` live state, so the server attaches a session. */
    val hasServerState: Boolean get() = false

    /**
     * Cloudflare edge freshness in seconds. A non-null value opts a successful HTML shell into
     * shared rendering with anonymous/public request context. Browsers revalidate every use;
     * Cloudflare serves the shell as fresh for this interval.
     */
    val sharedCacheMaxAgeSeconds: Long? get() = null

    /**
     * How long Cloudflare may serve an expired shared shell while it conditionally revalidates in
     * the background. Defaults to [sharedCacheMaxAgeSeconds] when shared caching is enabled.
     */
    val sharedCacheStaleWhileRevalidateSeconds: Long? get() = sharedCacheMaxAgeSeconds

    /**
     * True when the page requires a signed-in caller (`<page route="…" requireAuth>`). The server
     * redirects token-less requests to its configured sign-in route before rendering. This checks
     * token presence, not validity — the BML server stays a passthrough (the data plane rejects
     * bad tokens), so it is routing UX, not an auth boundary.
     */
    val requiresAuth: Boolean get() = false

    /** Render the page to [RenderContext.writer]. */
    suspend fun render(ctx: RenderContext)
}
