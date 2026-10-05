package bosca.bml.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** A custom tag (`<badge>`) Cmd-clicks to its `<component tag="badge">` declaration, across files. */
class BmlReferenceTest : BasePlatformTestCase() {

    fun testCustomTagResolvesToComponentDeclaration() {
        myFixture.addFileToProject("badge.bml", """<component tag="badge"><span><slot/></span></component>""")
        val file = myFixture.configureByText("home.bml", """<page route="/"><badge/></page>""")

        val reference = file.findReferenceAt(file.text.indexOf("badge"))
        assertNotNull("a custom tag should carry a reference", reference)
        val resolved = reference!!.resolve()
        assertNotNull("the reference should resolve to the component declaration", resolved)
        assertEquals("resolves into the declaring file", "badge.bml", resolved!!.containingFile.name)
    }

    fun testPlainHtmlTagDoesNotResolve() {
        val file = myFixture.configureByText("home.bml", """<page route="/"><div>x</div></page>""")
        val reference = file.findReferenceAt(file.text.indexOf("div"))
        // The reference is soft, so an HTML tag simply resolves to nothing (no error, no target).
        assertTrue("plain html tag resolves to nothing", reference == null || reference.resolve() == null)
    }
}
