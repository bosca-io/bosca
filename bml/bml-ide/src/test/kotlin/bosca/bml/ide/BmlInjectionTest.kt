package bosca.bml.ide

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Behavioral coverage of the plugin's marquee feature: that Kotlin is actually injected into
 * `<script server>` bodies and `{ … }` interpolation (not merely that the injector compiles).
 * Runs in a headless IDE with the plugin + bundled Kotlin plugin loaded.
 *
 * TypeScript resolution is covered separately by [BmlTsInjectionTest] against the Ultimate SDK's
 * bundled JavaScript/TypeScript plugin.
 */
class BmlInjectionTest : BasePlatformTestCase() {

    private fun allHosts(): Collection<BmlInjectionHost> {
        val hosts = PsiTreeUtil.findChildrenOfType(myFixture.file, BmlInjectionHost::class.java)
        check(hosts.isNotEmpty()) {
            val f = myFixture.file
            "no BmlInjectionHost — file=${f.javaClass.name} fileType=${f.fileType.name} lang=${f.language.id}"
        }
        return hosts
    }

    private fun hostOfType(type: IElementType): BmlInjectionHost =
        allHosts().first { it.node.elementType == type }

    private fun hostContaining(text: String): BmlInjectionHost =
        allHosts().first { it.text.contains(text) }

    private fun injectedLanguageId(host: BmlInjectionHost): String? =
        InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)?.firstOrNull()?.first?.language?.id

    private fun injectedText(host: BmlInjectionHost): String? =
        InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)?.firstOrNull()?.first?.text?.trim()

    fun testKotlinInjectsIntoServerScript() {
        myFixture.configureByText(BmlFileType, """<page route="/"><script server>val x = 1</script></page>""")
        assertEquals("kotlin", injectedLanguageId(hostOfType(BmlElementTypes.SCRIPT_SERVER_HOST)))
    }

    fun testKotlinInjectsIntoInterpolation() {
        myFixture.configureByText(BmlFileType, """<page route="/"><h1>{ user.name }</h1></page>""")
        assertEquals("kotlin", injectedLanguageId(hostOfType(BmlElementTypes.INTERPOLATION_HOST)))
    }

    fun testInjectedInterpolationExcludesBraces() {
        myFixture.configureByText(BmlFileType, """<page route="/"><h1>{ user.name }</h1></page>""")
        val host = hostOfType(BmlElementTypes.INTERPOLATION_HOST)
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)
        // The injected expression resolves with a `ctx`-scope prefix/suffix wrapper (invisible to the
        // editor); the interpolation's own `{ }` are excluded from the injected expression itself.
        val text = injected!!.first().first.text
        assertTrue("injects the interpolation expression", text.contains("user.name"))
        assertFalse("the interpolation braces are excluded", text.contains("{ user.name }"))
    }

    fun testKotlinInjectsIntoBoundAttributeValue() {
        myFixture.configureByText(BmlFileType, """<page route="/"><a :href="user.url">x</a></page>""")
        val host = hostContaining("user.url")
        assertEquals("kotlin", injectedLanguageId(host))
        assertTrue("injects the whole bound value (quotes excluded)", injectedText(host)!!.contains("user.url"))
    }

    fun testFeatureFlagWhenPredicateHasTypedEvaluationBinding() {
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><if flag="checkout" :when='flag.variationKey == "compact"'>x</if></page>""",
        )
        val host = hostContaining("flag.variationKey")
        val text = injectedText(host) ?: error("the feature flag predicate should be injected")
        assertTrue(
            "the predicate receives a typed evaluation binding:\n$text",
            text.contains("val flag: bosca.bml.features.FeatureFlagEvaluation = TODO()"),
        )
    }

    fun testClientFeatureFlagsUsesTheAuthoredModuleImportInsteadOfAFalseGlobal() {
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><script client>import { featureFlags } from "@bosca/bml"
                |void featureFlags.evaluateAll()</script></page>""".trimMargin(),
        )
        val host = hostContaining("featureFlags.evaluateAll")
        val text = injectedText(host) ?: error("the client script should be injected")
        assertTrue(text, text.contains("import { featureFlags } from \"@bosca/bml\""))
        assertFalse(text, text.contains("declare const featureFlags"))
    }

    fun testKotlinInjectsIntoInterpolatedAttributeValue() {
        myFixture.configureByText(BmlFileType, """<page route="/"><a class="btn-{ variant }">x</a></page>""")
        val host = hostContaining("variant")
        assertEquals("kotlin", injectedLanguageId(host))
        val text = injectedText(host)!!
        assertTrue("injects the { } region expression", text.contains("variant"))
        assertFalse("excludes the literal prefix outside the braces", text.contains("btn-"))
    }

    fun testKotlinInjectsIntoForIterable() {
        myFixture.configureByText(BmlFileType, """<page route="/"><for item in listOf("a")>x</for></page>""")
        val host = hostContaining("listOf")
        assertEquals("kotlin", injectedLanguageId(host))
        assertTrue("for iterable injects the Kotlin expression", injectedText(host)!!.contains("listOf"))
    }

    fun testPlainAttributeValueIsNotInjected() {
        myFixture.configureByText(BmlFileType, """<page route="/"><a class="c">x</a></page>""")
        val host = hostContaining("\"c\"")
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)
        assertTrue("plain attribute value must not be injected", injected == null || injected.isEmpty())
    }

    fun testStyleBodyIsNotInjectedAsKotlin() {
        myFixture.configureByText(BmlFileType, """<page route="/"><style>.x { color: red; }</style></page>""")
        // The <style> body is a single style host; its CSS `{ }` must NOT be a Kotlin interpolation host.
        assertTrue("a <style> body is one style host", allHosts().any { it.node.elementType == BmlElementTypes.STYLE_HOST })
        assertTrue(
            "CSS braces must not inject Kotlin",
            allHosts().none { it.node.elementType == BmlElementTypes.INTERPOLATION_HOST },
        )
    }

    fun testCssInjectsIntoStyleBodyWhenAvailable() {
        // CSS ships in most IDEs but not all SDKs; skip the language assertion where it's absent.
        if (com.intellij.lang.Language.findLanguageByID("CSS") == null) return
        myFixture.configureByText(BmlFileType, """<page route="/"><style>.x { color: red; }</style></page>""")
        assertEquals("CSS", injectedLanguageId(hostOfType(BmlElementTypes.STYLE_HOST)))
    }

    fun testPageProvidesResolvesInAnotherHost() {
        // A `provides` value used in another host resolves because its declaration is prepended (with
        // the body copied so the type is inferred) to that host's per-host injection.
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><script server provides="fruits">listOf("a")</script><a :href="fruits.first()">x</a></page>""",
        )
        val use = hostContaining("fruits.first()") // the :href bound attribute value host
        val text = injectedText(use) ?: error("the bound attribute value should be injected")
        assertTrue("the provides gets an explicit type in the use's fragment:\n$text", text.contains("val fruits: List<String> = TODO()"))
        assertEquals("kotlin", injectedLanguageId(use))
    }

    fun testInjectedDependencyIsTypedInItsRenderScope() {
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><inject name="clock" type="java.time.Clock" init="start"/><p>{ clock.instant() }</p></page>""",
        )
        val use = hostContaining("clock.instant()")
        val text = injectedText(use) ?: error("the interpolation should be injected")
        assertTrue("the injected dependency has its declared type:\n$text", text.contains("val clock: java.time.Clock = TODO()"))
        val declaration = BmlPageScope.findDeclarationElement(myFixture.file, "clock")
        assertNotNull("the inject name is a declaration target", declaration)
        assertTrue("goto lands on the inject name", declaration!!.text.contains("clock"))
    }

    fun testInjectedDependencyDoesNotLeakIntoSiblingComponentScope() {
        myFixture.configureByText(
            BmlFileType,
            """<component tag="clock-view"><inject name="clock" type="java.time.Clock"/><span>{ clock.instant() }</span></component>""" +
                """<component tag="date-view"><inject name="date" type="java.time.LocalDate"/><span>{ date.year }</span></component>""",
        )
        val clockText = injectedText(hostContaining("clock.instant()")) ?: error("clock interpolation missing")
        assertTrue(clockText.contains("val clock: java.time.Clock = TODO()"))
        assertFalse("a sibling component's injection must not leak:\n$clockText", clockText.contains("val date:"))

        val dateText = injectedText(hostContaining("date.year")) ?: error("date interpolation missing")
        assertTrue(dateText.contains("val date: java.time.LocalDate = TODO()"))
        assertFalse("a sibling component's injection must not leak:\n$dateText", dateText.contains("val clock:"))
    }

    fun testMultiLineProvidesServerScriptIsInjected() {
        // A representative exact shape: a multi-line <script server provides> body. Diagnostic showed NO injection
        // at this host in the IDE — reproduce it.
        myFixture.configureByText(
            BmlFileType,
            "<page route=\"/\"><script server provides=\"greeting\">\"hi\"</script>" +
                "<script server provides=\"fruits\">\n    listOf(\"alpha\", \"beta\", greeting)\n  </script></page>",
        )
        val host = hostContaining("listOf(\"alpha\"")
        assertEquals("kotlin", injectedLanguageId(host))
    }

    fun testServerScriptSeesOtherProvides() {
        // Inside one <script server>, another `provides` value must be in scope (e.g., greeting used
        // inside the fruits script doesn't autocomplete).
        myFixture.configureByText(
            BmlFileType,
            "<page route=\"/\"><script server provides=\"greeting\">\"hi\"</script>" +
                "<script server provides=\"fruits\">listOf(greeting)</script></page>",
        )
        val host = hostContaining("listOf(greeting)") // the fruits <script server> body
        val text = injectedText(host) ?: error("the server script should be injected")
        assertTrue("the other provides is declared in this script's fragment:\n$text", text.contains("val greeting: String = TODO()"))
    }

    fun testMultiLineProvidesBodyGivesTheUseAType() {
        // Mirrors home.bml: a multi-line <script server provides> gets an explicit List<String> type so
        // `fruits.reversed()` / `.size` resolve (IntelliJ won't infer an injected-prefix initializer).
        myFixture.configureByText(
            BmlFileType,
            "<page route=\"/\">\n<script server provides=\"fruits\">\n  listOf(\"a\", \"b\")\n</script>\n" +
                "<item-list :items=\"fruits.reversed()\"/></page>",
        )
        val use = hostContaining("fruits.reversed()") // the :items bound value
        val text = injectedText(use) ?: error("the page Kotlin should be injected")
        assertTrue("the provides gets an explicit List<String> type:\n$text", text.contains("val fruits: List<String> = TODO()"))
    }

    fun testComponentPropResolvesInItsBody() {
        // A component's <prop> must resolve in its body (e.g., can't Cmd-click `items` in item-list).
        myFixture.configureByText(
            BmlFileType,
            "<component tag=\"item-list\"><prop name=\"items\" type=\"List<String>\" required/>" +
                "<h2>{ items.size }</h2></component>",
        )
        val use = hostContaining("items.size")
        val text = injectedText(use) ?: error("the component body should be injected")
        assertTrue("the prop is declared with its type:\n$text", text.contains("val items: List<String> = TODO()"))
        assertTrue("the prop use is in the same fragment:\n$text", text.contains("items.size"))
    }

    fun testProvidesConstructorCallInfersClassType() {
        // A `provides` whose body is a constructor call gets that class as its type, so `m.foo` resolves.
        myFixture.configureByText(
            BmlFileType,
            "<page route=\"/\"><script server provides=\"m\">bosca.bml.sample.CounterModel()</script>" +
                "<a :href=\"m.count\">x</a></page>",
        )
        val use = hostContaining("m.count") // the :href bound value
        val text = injectedText(use) ?: error("the bound attr value should be injected")
        assertTrue(
            "a provides constructor call gets the class type:\n$text",
            text.contains("val m: bosca.bml.sample.CounterModel = TODO()"),
        )
    }

    fun testClassAttributeReferencesCssClass() {
        if (com.intellij.lang.Language.findLanguageByID("CSS") == null) return
        myFixture.configureByText(
            BmlFileType,
            "<component tag=\"x\"><style scoped>.badge { color: red; }</style>" +
                "<span class=\"badge\">y</span></component>",
        )
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, BmlInjectionHost::class.java)
            .first { it.node.elementType == BmlElementTypes.ATTR_VALUE_HOST && it.text.contains("badge") }
        val refs = host.references
        val resolved = refs.firstOrNull()?.resolve()
        println("=== CSSREF count=${refs.size} resolved=${resolved?.javaClass?.simpleName} text='${resolved?.text}' ===")
        assertTrue("class=\"badge\" must contribute a reference", refs.isNotEmpty())
        assertNotNull("class=\"badge\" must resolve to the .badge CssClass", resolved)
    }

    fun testStyleInjectionPrependsGlobalThemeCustomProperties() {
        if (com.intellij.lang.Language.findLanguageByID("CSS") == null) return
        // The site theme (the app.css tier) lives in src/main/client/*.css — its custom properties
        // are prepended to every <style> injection so `var(--divider)` resolves.
        myFixture.addFileToProject("src/main/client/site.css", ":root { --divider: rgba(0,0,0,0.1); }")
        val bml = myFixture.addFileToProject(
            "src/main/bml/components/card.bml",
            """<component tag="card"><style scoped>.card { border: 1px solid var(--divider); }</style></component>""",
        )
        myFixture.configureFromExistingVirtualFile(bml.virtualFile)
        val host = hostOfType(BmlElementTypes.STYLE_HOST)
        val text = injectedText(host) ?: error("the style body should be injected")
        assertTrue("the theme's custom properties are in the fragment:\n$text", text.contains("--divider: rgba(0,0,0,0.1);"))
        assertEquals("CSS", injectedLanguageId(host))
    }

    fun testStyleInjectionHasNoPrefixWithoutAClientTheme() {
        if (com.intellij.lang.Language.findLanguageByID("CSS") == null) return
        myFixture.configureByText(BmlFileType, """<page route="/"><style>.x { color: red; }</style></page>""")
        val text = injectedText(hostOfType(BmlElementTypes.STYLE_HOST)) ?: error("style should inject")
        assertFalse("no synthetic :root block without src/main/client CSS", text.contains(":root"))
    }

    fun testForBodyHostSeesLoopVariable() {
        // A host inside `<for item in …>` gets the loop re-opened as a REAL Kotlin for — `item`
        // exists with its inferred element type (autocomplete + Cmd-click for `{ item.href }`).
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><for item in listOf("a")><li>{ item.length }</li></for></page>""",
        )
        val use = hostContaining("item.length")
        val text = injectedText(use) ?: error("the interpolation should be injected")
        assertTrue("the enclosing for is re-opened in the fragment:\n$text", text.contains("for (item in listOf(\"a\")) {"))
    }

    fun testNestedForsBothInScope() {
        myFixture.configureByText(
            BmlFileType,
            "<page route=\"/\"><for row in rows><for cell in row.cells><b>{ cell.text }</b></for></for></page>",
        )
        val use = hostContaining("cell.text")
        val text = injectedText(use) ?: error("the interpolation should be injected")
        assertTrue("outer loop in scope:\n$text", text.contains("for (row in rows) {"))
        assertTrue("inner loop in scope:\n$text", text.contains("for (cell in row.cells) {"))
    }

    fun testForHeaderExcludesItsOwnBindingButSeesOuterLoops() {
        myFixture.configureByText(
            BmlFileType,
            "<page route=\"/\"><for row in rows><for cell in row.cells><b>x</b></for></for></page>",
        )
        val header = hostContaining("cell in row.cells") // the inner for's own header host
        val text = injectedText(header) ?: error("the for header should be injected")
        assertTrue("the OUTER loop is in the header's scope:\n$text", text.contains("for (row in rows) {"))
        // Exactly one `for (cell …` — the header's own flow wrapper; NOT also re-opened in the prefix.
        assertEquals("its own binding must not be duplicated:\n$text", 1, Regex("for \\(cell in").findAll(text).count())
    }

    fun testClosedForIsOutOfScope() {
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><for item in listOf("a")><li>x</li></for><p>{ tail }</p></page>""",
        )
        val use = hostContaining("tail")
        val text = injectedText(use) ?: error("the interpolation should be injected")
        assertFalse("a closed for's variable is gone:\n$text", text.contains("for (item"))
    }

    fun testUninferableProvidesKeepsRealInitializer() {
        // A provides body that's a call into project code (`loadPage(ctx)`) can't be typed textually —
        // the declaration keeps the live expression so the anchored resolution fragment infers the
        // real return type (goto + member completion on `page.items`).
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><script server provides="page">loadPage(ctx)</script><a :href="page.items">x</a></page>""",
        )
        val use = hostContaining("page.items")
        val text = injectedText(use) ?: error("the bound attribute should be injected")
        assertTrue("the provides keeps its initializer:\n$text", text.contains("val page = run {\nloadPage(ctx)\n}"))
    }

    fun testFormActionHostGetsTypedFormObject() {
        // `@submit="m.create(form.name, form.aiEnabled, ctx)"`: the enclosing form's fields become a
        // typed `form` object (checkbox → Boolean, everything else → String) even though the fields
        // appear AFTER the @submit attribute.
        myFixture.configureByText(
            BmlFileType,
            "<page route=\"/\"><form @submit=\"m.create(form.name, form.aiEnabled, ctx)\">" +
                "<input type=\"text\" name=\"name\" required/>" +
                "<select name=\"kind\"><option value=\"x\">x</option></select>" +
                "<input type=\"checkbox\" name=\"aiEnabled\"/>" +
                "</form></page>",
        )
        val use = hostContaining("form.name")
        val text = injectedText(use) ?: error("the @submit value should be injected")
        assertTrue("form object declared:\n$text", text.contains("val form = __BmlForm()"))
        assertTrue("text input is String:\n$text", text.contains("val `name`: String = TODO();"))
        assertTrue("select is String:\n$text", text.contains("val `kind`: String = TODO();"))
        assertTrue("checkbox is Boolean:\n$text", text.contains("val `aiEnabled`: Boolean = TODO();"))
    }

    fun testHostOutsideAnyFormHasNoFormObject() {
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><form><input name="x"/></form><h1>{ title }</h1></page>""",
        )
        val use = hostContaining("title")
        val text = injectedText(use) ?: error("the interpolation should be injected")
        assertFalse("no form object outside a form:\n$text", text.contains("__BmlForm"))
    }

    fun testFormFieldDeclarationElementIsTheNameAttribute() {
        myFixture.configureByText(
            BmlFileType,
            "<page route=\"/\"><form @submit=\"m.go(form.email)\"><input name=\"email\"/></form></page>",
        )
        val decl = BmlPageScope.findDeclarationElement(myFixture.file, "email")
        assertNotNull("form field name attr is the declaration", decl)
        assertEquals("\"email\"", decl!!.text)
    }

    fun testServerScriptSeesCurrentRenderContext() {
        // Mirrors the generated file's hoisted import: embedded server code calls
        // `currentRenderContext()` bare, so every fragment declares a typed local equivalent.
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><script server provides="x">currentRenderContext().params</script></page>""",
        )
        val host = hostContaining("currentRenderContext().params")
        val text = injectedText(host) ?: error("the server script should be injected")
        assertTrue(
            "the fragment declares currentRenderContext():\n$text",
            text.contains("suspend fun currentRenderContext(): bosca.bml.render.RenderContext = ctx"),
        )
        assertTrue(
            "the fragment declares client():\n$text",
            text.contains("suspend fun client(): bosca.bml.graphql.GraphQLClient = ctx.gql"),
        )
    }

    fun testForHeaderInjectsAsForLoop() {
        // The <for> header is wrapped as a real `for (… ) {}` so its binding + iterable resolve.
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><for item in listOf("a")><li>{ item }</li></for></page>""",
        )
        val flow = hostContaining("item in listOf") // the <for> header host
        val text = injectedText(flow) ?: error("the for header should be injected")
        assertTrue("the for header is wrapped as a Kotlin for-loop:\n$text", text.contains("for ("))
    }
}
