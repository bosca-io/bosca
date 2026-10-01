package bosca.bml.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Behavioral coverage of BML autocomplete: tag names after `<` and attribute names inside a tag. */
class BmlCompletionTest : BasePlatformTestCase() {

    fun testTagCompletionOffersSpecialHtmlAndComponentTags() {
        myFixture.addFileToProject("card.bml", """<component tag="card"><slot/></component>""")
        myFixture.configureByText("home.bml", """<page route="/"><<caret></page>""")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: error("no completion list")
        assertTrue("special tag suggested: $items", items.containsAll(listOf("prop", "inject")))
        assertTrue("html tag suggested: $items", items.contains("div"))
        assertTrue("declared component suggested: $items", items.contains("card"))
    }

    fun testTagCompletionFiltersByPrefix() {
        myFixture.configureByText(BmlFileType, """<page route="/"><pr<caret></page>""")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: error("no completion list")
        assertTrue("typing <pr suggests prop: $items", items.contains("prop"))
    }

    fun testComponentAttributeCompletionOffersDeclaredProps() {
        myFixture.addFileToProject(
            "badge.bml",
            "<component tag=\"badge\"><prop name=\"label\" type=\"String\" required/>" +
                "<prop name=\"tone\" type=\"String\"/><span/></component>",
        )
        myFixture.configureByText("home.bml", """<page route="/"><badge <caret>/></page>""")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: error("no completion list")
        assertTrue("declared props suggested: $items", items.contains("label") && items.contains("tone"))
        assertTrue("bound form suggested: $items", items.contains(":label"))
    }

    fun testPropAttributeCompletion() {
        myFixture.configureByText(BmlFileType, """<component tag="x"><prop <caret>/></component>""")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: error("no completion list")
        assertTrue("prop attributes suggested: $items", items.containsAll(listOf("name", "type", "required", "default")))
    }

    fun testComponentScopeCompletionOffersPageAndSite() {
        myFixture.configureByText(BmlFileType, """<component tag="free-access" scope="<caret>"></component>""")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: error("no completion list")
        assertTrue("component scopes suggested: $items", items.containsAll(listOf("page", "site")))
    }

    fun testInjectAttributeCompletion() {
        myFixture.configureByText(BmlFileType, """<page route="/"><inject <caret>/></page>""")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: error("no completion list")
        assertTrue(
            "inject attributes suggested: $items",
            items.containsAll(listOf("name", "type", "provider", "init", "server", "scope", "clear-on-sign-out")),
        )
    }

    fun testPageAttributeCompletionOffersContentType() {
        myFixture.configureByText(BmlFileType, """<page route="/feed.json" <caret>></page>""")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: error("no completion list")
        assertTrue("page content type suggested: $items", items.contains("contentType"))
    }

    fun testRouteAttributeCompletionOffersPathAndContentType() {
        myFixture.configureByText(BmlFileType, """<route <caret>></route>""")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: error("no completion list")
        assertTrue("route attributes suggested: $items", items.containsAll(listOf("path", "contentType")))
    }

    fun testSharedCacheAttributesAndDeferredRenderModeAreCompleted() {
        myFixture.configureByText(BmlFileType, """<page route="/" <caret>></page>""")
        myFixture.completeBasic()
        val pageItems = myFixture.lookupElementStrings ?: error("no page completion list")
        assertTrue(
            "shared-cache attributes suggested: $pageItems",
            pageItems.containsAll(listOf("cache", "maxAge", "staleWhileRevalidate")),
        )

        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><island name="account" render="<caret>"></island></page>""",
        )
        myFixture.completeBasic()
        val modes = myFixture.lookupElementStrings ?: error("no render-mode completion list")
        assertTrue("deferred render suggested: $modes", modes.containsAll(listOf("request", "prerender", "deferred")))
    }

    fun testServerScriptAttributeCompletionOffersStateLifetimeControls() {
        myFixture.configureByText(BmlFileType, """<page route="/"><script server <caret>></script></page>""")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: error("no completion list")
        assertTrue(
            "server state lifetime attributes suggested: $items",
            items.containsAll(listOf("provides", "scope", "clear-on-sign-out")) && "persist" !in items,
        )
    }

    fun testClientScriptAttributeCompletionDoesNotOfferServerStateScope() {
        myFixture.configureByText(BmlFileType, """<page route="/"><script client <caret>></script></page>""")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: error("no completion list")
        assertTrue("client script attributes suggested: $items", "scoped" in items)
        assertFalse("server-only state scope must not be suggested: $items", "scope" in items || "provides" in items)
    }

    fun testLiveStateScopeValueCompletionIncludesPersistentLocalStorage() {
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><script server provides="draft" scope="<caret>">Draft()</script></page>""",
        )
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: error("no completion list")
        assertTrue(
            "live state scopes suggested: $items",
            items.containsAll(listOf("page", "client-session", "client-local", "server-session")),
        )
    }

    fun testInjectLiveStateScopeValueCompletion() {
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><inject server name="draft" type="example.Draft" scope="<caret>"/></page>""",
        )
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: error("no completion list")
        assertTrue(
            "inject live state scopes suggested: $items",
            items.containsAll(listOf("page", "client-session", "client-local", "server-session")),
        )
    }
}
