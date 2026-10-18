package bosca.bml.render

/**
 * Compile-time metadata for a BML component, emitted into the generated `bml.generated.BmlComponents`
 * registry. The asset pipeline (the three-tier model: global / per-page / per-component) uses it to:
 *   - serve a component's scoped CSS as a cached per-component stylesheet ([styles]), and
 *   - expand a page's directly-referenced components to the transitive set actually rendered, via the
 *     [deps] edges, so only those component chunks are linked ("included based on what's rendered").
 *
 * [tag] is the runtime identity: an application's local tag or a library's coordinate-qualified id,
 * independent of its import alias. [scope] equals [tag] when it has `<style scoped>`, else null.
 * [clientModule] names the component's own client bundle.
 */
data class BmlComponentInfo(
    val tag: String,
    val scope: String?,
    val styles: String,
    val deps: List<String>,
    val clientModule: String? = null,
    /** True when the component declares `scope="server-session"` live state — pages rendering it need a session. */
    val hasServerState: Boolean = false,
    /** Dependencies rendered during the initial request, excluding component calls under deferred islands. */
    val eagerDeps: List<String> = deps,
    /** True when the eager component body evaluates a request-identity feature flag. */
    val hasEagerFeatureFlags: Boolean = false,
    /** Stable digest of the BML source and compiler revision that generated this component. */
    val renderRevision: String = "",
    /** Deferred renderers declared by this component, registered independently of sliver rendering. */
    val deferredRenderers: List<BmlDeferredRenderer> = emptyList(),
)
