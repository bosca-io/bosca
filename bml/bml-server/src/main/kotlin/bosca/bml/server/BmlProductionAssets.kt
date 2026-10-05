package bosca.bml.server

import bosca.bml.render.BmlComponentInfo
import bosca.bml.render.BmlPageRenderer

/**
 * The production CSS shape: a page downloads at most **one page stylesheet** on top of
 * **one normalized global stylesheet** — never per-component chunks. Pure logic, computed once at
 * server construction:
 *   - the **shared set** is the components with scoped styles rendered by *every* page (the
 *     intersection of all page closures) — their CSS folds into the served `app.css`,
 *   - each page's **merged stylesheet** is its closure minus the shared set, concatenated in the
 *     same stable (sorted) order the dev chunks are linked in, served at
 *     `/_bml/css/<slug>.page.css`.
 *
 * Dev mode keeps the per-component chunks so an edit invalidates one small file, not every page's
 * merged stylesheet.
 */
object BmlProductionAssets {

    /** Suffix distinguishing merged page stylesheets from per-component chunks under the same route. */
    const val PAGE_CSS_SUFFIX = ".page.css"

    /**
     * Suffix of the per-page production bundle in the client dir. The bundler (`bundle.mjs
     * --manifest … --prod-out …`) names bundles with the same route slug algorithm, so the server
     * and the build agree without a lookup file.
     */
    const val PAGE_JS_SUFFIX = ".page.js"

    data class Plan(
        /** Styled components every page renders — folded into the served global stylesheet. */
        val sharedTags: List<String>,
        /** The shared components' CSS, appended to the site's global stylesheet. */
        val sharedCss: String,
        /** Route -> merged page CSS (routes with nothing beyond the shared set are absent). */
        val pageCss: Map<String, String>,
        /** Route -> the page's single production bundle file name (routes with no client code are absent). */
        val pageJsFile: Map<String, String>,
        /** Route -> served slug (routes needing neither page CSS nor page JS are absent). */
        val pageSlug: Map<String, String>,
    ) {
        private val routeBySlug: Map<String, String> = pageSlug.entries.associate { (route, slug) -> slug to route }

        /** The merged stylesheet for a served `<slug>.page.css` name, or null when unknown. */
        fun cssForSlug(slug: String): String? = routeBySlug[slug]?.let { pageCss[it] }

        fun pageCssUrl(route: String): String? =
            if (route in pageCss) "${BmlStyleAssets.COMPONENT_CSS_PREFIX}${pageSlug.getValue(route)}$PAGE_CSS_SUFFIX" else null
    }

    /** `/lists/{id}` -> `lists-id`, `/` -> `index`. Uniqueness is enforced in [plan]. */
    fun slug(route: String): String =
        route.trim('/').replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase().ifEmpty { "index" }

    fun plan(pages: List<BmlPageRenderer>, byTag: Map<String, BmlComponentInfo>): Plan {
        // Each page's styled closure, in the stable sorted order closure() returns — the merge
        // order matches the order the dev chunks were linked in, so the cascade is unchanged.
        val styledClosures: Map<String, List<String>> = pages.associate { page ->
            page.route to BmlStyleAssets.closure(page.componentTags, byTag)
                .filter { tag -> byTag[tag]?.styles?.isNotBlank() == true }
        }
        val shared: List<String> = styledClosures.values
            .map { it.toSet() }
            .reduceOrNull { a, b -> a intersect b }
            .orEmpty()
            .sorted()
        val sharedCss = shared.mapNotNull { byTag[it]?.styles }.joinToString("\n")

        // A page has a production bundle when anything on it carries client code — its own
        // `<script client>`/live state, or any closure component's.
        val pagesByRoute = pages.associateBy { it.route }
        fun hasClientCode(page: BmlPageRenderer): Boolean =
            page.clientModule != null ||
                BmlStyleAssets.closure(page.componentTags, byTag).any { byTag[it]?.clientModule != null }

        val pageCss = linkedMapOf<String, String>()
        val pageJsFile = linkedMapOf<String, String>()
        val pageSlug = linkedMapOf<String, String>()
        val takenSlugs = mutableSetOf<String>()
        for (route in styledClosures.keys.sorted()) {
            val own = styledClosures.getValue(route).filterNot { it in shared }
            val needsJs = hasClientCode(pagesByRoute.getValue(route))
            if (own.isEmpty() && !needsJs) continue
            // Deterministic slug uniqueness: routes sort first, collisions suffix -2, -3, …
            var candidate = slug(route)
            var n = 2
            while (!takenSlugs.add(candidate)) candidate = "${slug(route)}-${n++}"
            pageSlug[route] = candidate
            if (own.isNotEmpty()) pageCss[route] = own.mapNotNull { byTag[it]?.styles }.joinToString("\n")
            if (needsJs) pageJsFile[route] = "$candidate$PAGE_JS_SUFFIX"
        }
        return Plan(shared, sharedCss, pageCss, pageJsFile, pageSlug)
    }
}
