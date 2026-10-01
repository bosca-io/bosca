package bosca.bml.server

import bosca.bml.render.BmlComponentInfo
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.RenderContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BmlProductionAssetsTest {

    private fun page(route: String, vararg tags: String): BmlPageRenderer = object : BmlPageRenderer {
        override val route: String = route
        override val componentTags: List<String> = tags.toList()
        override suspend fun render(ctx: RenderContext) = Unit
    }

    private fun component(tag: String, styles: String, deps: List<String> = emptyList()): BmlComponentInfo =
        BmlComponentInfo(tag, tag.takeIf { styles.isNotEmpty() }, styles, deps)

    private val byTag = listOf(
        component("site-head", ".site-head{}", deps = listOf("mast-link")),
        component("mast-link", ".mast-link{}"),
        component("badge", ".badge{}"),
        component("card", ".card{}", deps = listOf("badge")),
        component("plain", "", deps = emptyList()), // no styles -> never in any css tier
    ).associateBy { it.tag }

    @Test
    fun `shared set is the styled intersection of every page's closure`() {
        val plan = BmlProductionAssets.plan(
            listOf(
                page("/", "site-head", "card"),
                page("/about", "site-head", "plain"),
            ),
            byTag,
        )
        // site-head + its dep mast-link render on both pages; card/badge only on "/".
        assertEquals(listOf("mast-link", "site-head"), plan.sharedTags)
        assertEquals(".mast-link{}\n.site-head{}", plan.sharedCss)
        // "/" keeps only its non-shared styles, merged in stable sorted order.
        assertEquals(".badge{}\n.card{}", plan.pageCss["/"])
        // "/about" has nothing beyond the shared set -> no page stylesheet at all.
        assertNull(plan.pageCss["/about"])
        assertEquals("/_bml/css/index.page.css", plan.pageCssUrl("/"))
        assertNull(plan.pageCssUrl("/about"))
    }

    @Test
    fun `a page with no components empties the shared set`() {
        val plan = BmlProductionAssets.plan(
            listOf(page("/", "site-head"), page("/bare")),
            byTag,
        )
        assertEquals(emptyList(), plan.sharedTags)
        assertEquals("", plan.sharedCss)
        assertEquals(".mast-link{}\n.site-head{}", plan.pageCss["/"])
    }

    @Test
    fun `slugs derive from routes and stay unique`() {
        assertEquals("index", BmlProductionAssets.slug("/"))
        assertEquals("lists-id", BmlProductionAssets.slug("/lists/{id}"))
        assertEquals("cart", BmlProductionAssets.slug("/cart"))
        // Two routes that slug identically get deterministic suffixes. The bare page empties the
        // shared set so both pages keep their own stylesheet.
        val plan = BmlProductionAssets.plan(
            listOf(page("/a-b", "badge"), page("/a/b", "card"), page("/bare")),
            byTag,
        )
        assertEquals(setOf("a-b", "a-b-2"), plan.pageSlug.values.toSet())
        // The slug lookup used by the serving route resolves back to the right css.
        val slugOfAB = plan.pageSlug.getValue("/a-b")
        assertEquals(plan.pageCss.getValue("/a-b"), plan.cssForSlug(slugOfAB))
    }

    @Test
    fun `CSS-only page reserves a colliding slug before a client page`() {
        val clientPage = object : BmlPageRenderer {
            override val route = "/a/b"
            override val clientModule = "ClientPage.js"
            override suspend fun render(ctx: RenderContext) = Unit
        }
        val plan = BmlProductionAssets.plan(listOf(page("/a-b", "badge"), clientPage), byTag)

        assertEquals("a-b", plan.pageSlug["/a-b"])
        assertEquals("a-b-2.page.js", plan.pageJsFile["/a/b"])
    }

    @Test
    fun `page js bundles cover pages whose only client code is a component's`() {
        val comps = mapOf(
            "widget" to BmlComponentInfo("widget", null, "", emptyList(), clientModule = "WidgetPage.js"),
            "badge" to component("badge", ".badge{}"),
        )
        val own = object : BmlPageRenderer {
            override val route: String = "/own"
            override val clientModule: String = "OwnPage.js"
            override suspend fun render(ctx: RenderContext) = Unit
        }
        val plan = BmlProductionAssets.plan(
            listOf(own, page("/via-comp", "widget"), page("/none", "badge"), page("/bare")),
            comps,
        )
        assertEquals("own.page.js", plan.pageJsFile["/own"])
        assertEquals("via-comp.page.js", plan.pageJsFile["/via-comp"])
        // badge has styles but no client code: page CSS yes, page JS no.
        assertNull(plan.pageJsFile["/none"])
        assertEquals(".badge{}", plan.pageCss["/none"])
    }

    @Test
    fun `production links are the global plus at most one page stylesheet`() {
        val links = BmlStyleAssets.cssLinksProduction(true, "/_bml/css/index.page.css", "123")
        assertEquals(
            """<link rel="stylesheet" href="/_bml/app.css?_ts=123"><link rel="stylesheet" href="/_bml/css/index.page.css?_ts=123">""",
            links,
        )
        assertEquals(
            """<link rel="stylesheet" href="/_bml/app.css">""",
            BmlStyleAssets.cssLinksProduction(true, null),
        )
        assertTrue(BmlStyleAssets.cssLinksProduction(false, null).isEmpty())
    }
}
