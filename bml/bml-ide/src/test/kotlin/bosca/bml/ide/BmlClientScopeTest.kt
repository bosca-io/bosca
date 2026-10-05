package bosca.bml.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The site-level client scope: `src/main/client` discovery from a `.bml` file, theme
 * custom-property extraction (site.css), and the `on<event>` handler-name collection that mirrors
 * the compiler's `clientHandlerNames`. Browser globals (site.ts's `auth`) are deliberately NOT
 * part of the scope — the client entry's `declare global` resolves project-wide on its own, and
 * re-declaring per fragment caused "multiple implementations" multi-resolve.
 */
class BmlClientScopeTest : BasePlatformTestCase() {

    // ── custom-property extraction (pure) ──

    fun testCollectsCustomPropertiesWithValues() {
        val css = """
            :root {
              --divider: rgba(26, 26, 26, 0.10);
              --sage: #689E7E;
              --accent-soft: color-mix(in srgb, var(--accent) 10%, var(--bg));
            }
            [data-theme="dark"] { --divider: rgba(242, 239, 233, 0.12); }
            .card { border: 1px solid var(--divider); }
        """.trimIndent()
        val props = LinkedHashMap<String, String>()
        BmlClientScope.collectCustomProperties(css, props)
        // First (light-theme) definition wins; `var(--divider)` USES don't register as definitions.
        assertEquals("rgba(26, 26, 26, 0.10)", props["--divider"])
        assertEquals("#689E7E", props["--sage"])
        assertEquals("color-mix(in srgb, var(--accent) 10%, var(--bg))", props["--accent-soft"])
        assertEquals(setOf("--divider", "--sage", "--accent-soft"), props.keys)
    }

    // ── client-dir discovery + scope assembly ──

    fun testScopeDiscoversClientDirSiblingOfBmlRoot() {
        myFixture.addFileToProject("src/main/client/site.css", ":root { --divider: red; --wide: 1000px; }")
        val bml = myFixture.addFileToProject("src/main/bml/pages/home.bml", """<page route="/"/>""")
        val scope = BmlClientScope.scope(bml)
        assertEquals(":root {\n  --divider: red;\n  --wide: 1000px;\n}\n", scope.cssPrefix)
    }

    fun testScopeIsEmptyOutsideTheLayoutConvention() {
        val bml = myFixture.addFileToProject("elsewhere/page.bml", """<page route="/"/>""")
        assertNull(BmlClientScope.scope(bml).cssPrefix)
    }

    // ── window handler names (mirrors BmlClientCodeGenerator.clientHandlerNames) ──

    fun testWindowHandlerNamesFromStaticOnAttributes() {
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><form onsubmit="signIn(event)"><button onclick="continueWithGoogle(event)">g</button>""" +
                """<button onclick="window.print()">p</button></form></page>""",
        )
        // `window.print()` isn't a bare `name(…)` call — assumed already global, not collected.
        assertEquals(listOf("signIn", "continueWithGoogle"), BmlClientScope.windowHandlerNames(myFixture.file))
    }

    fun testBoundAndInterpolatedAttributesAreNotWindowHandlers() {
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><button @click="increment()">+</button>""" +
                """<button onclick="go({ id })">x</button></page>""",
        )
        // `@click` is a live-island action (Kotlin-side); an interpolated value isn't pure text.
        assertEquals(emptyList<String>(), BmlClientScope.windowHandlerNames(myFixture.file))
    }
}
