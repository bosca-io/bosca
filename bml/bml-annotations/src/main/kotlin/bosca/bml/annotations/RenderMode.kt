package bosca.bml.annotations

/**
 * When a page or island's server HTML is produced.
 */
enum class RenderMode {
    /** Rendered per request (SSR). Default. */
    Request,

    /**
     * Pre-rendered at build time into the compiled snapshot; the
     * static HTML still carries island mount markers and hydrates client-side.
     */
    Prerender,

    /**
     * Render the island body through a browser-initiated request after the page shell loads.
     * Islands only: a page cannot be deferred, so `@BmlPage(render = Deferred)` has no meaning.
     */
    Deferred,
}
