package bosca.bml.render

/**
 * A component that can be rendered by tag at runtime — the contract behind the **sliver re-render**:
 * the server looks a component up in `bml.generated.BmlComponents.renderers`, hands it
 * the props posted by the client, and returns the rendered HTML fragment for the island to swap in.
 *
 * Each generated component `object` implements this; its [render] is the same one a page calls when it
 * renders the component inline, so a first render and a re-render produce identical markup.
 */
fun interface BmlComponentRenderer {
    suspend fun render(ctx: RenderContext, props: Map<String, Any?>, slot: suspend () -> Unit)

    /**
     * The component's live-island action dispatchers (one per server-bound state the component
     * declares — see [BmlIslandActionDispatcher]). Page-lifetime component keys are per-instance
     * (`"<tag>.<binding>:<key>"`); site-lifetime components use the exact stable
     * `"<tag>.<binding>"` key. Both register there in
     * `bml.generated.BmlIslands.componentDispatchers`. Empty for view-only components.
     */
    val islandActionDispatchers: List<BmlIslandActionDispatcher> get() = emptyList()

    /** Deferred island renderers declared by this component. */
    val deferredRenderers: List<BmlDeferredRenderer> get() = emptyList()
}
