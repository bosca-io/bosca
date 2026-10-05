package bosca.bml.codegen

/**
 * Compile-time asset metadata for one generated page, collected by [BmlCodeGenerator] for the
 * site metrics manifest. [clientModule] is the page's bundled island module (when the
 * page carries a `<script client>` or a live state), [componentTags] the components the page
 * references directly.
 */
data class BmlPageMeta(
    val objectName: String,
    val route: String,
    val clientModule: String?,
    val componentTags: List<String>,
)

/**
 * Compile-time asset metadata for one generated component. [styles] is the component's scoped
 * CSS (empty when it has none — matches `BmlComponentInfo.styles`), [deps] the components it
 * references (the closure edges), [clientModule] its bundled module when its file carries client
 * script or live state.
 */
data class BmlComponentMeta(
    val tag: String,
    val styles: String,
    val deps: List<String>,
    val clientModule: String?,
)

/**
 * The site metrics manifest: a line-based, tab-separated file the compiler plugin writes
 * to the metrics output directory so the Gradle `bmlMetrics` task can report per-page first-load
 * payloads without compiling anything itself.
 *
 * Format (v1) — one header line, then one `P` line per page:
 * ```
 * bml-metrics⇥1
 * P⇥<object>⇥<route>⇥<pageJs|->⇥<css tags csv|->⇥<component js csv|->
 * ```
 * The css/js columns are the page's **transitive** component closure, resolved here at compile
 * time with the same semantics the server uses to link assets: every closure component with
 * scoped styles contributes its `css/<tag>.css` chunk, and every closure component with a client
 * module contributes that module (deduplicated, excluding the page's own module — mirroring
 * `BmlServer`'s per-page injection and `BmlStyleAssets.closure`).
 */
object BmlMetricsManifest {
    const val FORMAT: String = "bml-metrics"
    const val VERSION: Int = 1
    const val FILE_NAME: String = "manifest.tsv"

    /** Subdirectory of the metrics output dir holding one `<tag>.css` chunk per styled component. */
    const val CSS_DIR: String = "css"

    /** Expands directly-referenced [tags] to the transitive component set via each component's deps. */
    fun closure(tags: List<String>, componentsByTag: Map<String, BmlComponentMeta>): Set<String> {
        val seen = linkedSetOf<String>()
        val queue = ArrayDeque(tags)
        while (queue.isNotEmpty()) {
            val tag = queue.removeFirst()
            if (!seen.add(tag)) continue
            componentsByTag[tag]?.deps?.forEach { dep -> if (dep !in seen) queue.addLast(dep) }
        }
        return seen
    }

    /** Renders the manifest text for [pages] against the full [components] set. */
    fun render(pages: List<BmlPageMeta>, components: List<BmlComponentMeta>): String {
        val byTag = components.associateBy { it.tag }
        fun csv(values: List<String>): String = if (values.isEmpty()) "-" else values.joinToString(",")
        return buildString {
            append(FORMAT).append('\t').append(VERSION).append('\n')
            for (page in pages.sortedBy { it.objectName }) {
                val closure = closure(page.componentTags, byTag)
                val cssTags = closure.filter { byTag[it]?.styles?.isNotBlank() == true }.sorted()
                val componentJs = closure.mapNotNull { byTag[it]?.clientModule }
                    .filterNot { it == page.clientModule }
                    .distinct()
                    .sorted()
                append("P\t").append(page.objectName).append('\t').append(page.route)
                    .append('\t').append(page.clientModule ?: "-")
                    .append('\t').append(csv(cssTags))
                    .append('\t').append(csv(componentJs))
                    .append('\n')
            }
        }
    }
}
