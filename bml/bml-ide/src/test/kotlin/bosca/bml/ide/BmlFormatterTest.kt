package bosca.bml.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Reformat behavior: tag indentation (2-space default), attribute spacing, `<else>` clause
 * alignment, and embedded script/style bodies re-indented to one level inside their tag with
 * the author's relative indentation preserved.
 */
class BmlFormatterTest : BasePlatformTestCase() {

    private fun doTest(before: String, after: String) {
        myFixture.configureByText(BmlFileType, before)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        assertEquals(after, myFixture.file.text)
        // Idempotency: reformatting the formatted text must be a no-op.
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        assertEquals(after, myFixture.file.text)
    }

    fun testIndentsNestedTags() {
        doTest(
            """
            <page route="/">
            <div>
            <h1>Hello</h1>
            </div>
            </page>
            """.trimIndent(),
            """
            <page route="/">
              <div>
                <h1>Hello</h1>
              </div>
            </page>
            """.trimIndent(),
        )
    }

    fun testNormalizesAttributeSpacing() {
        doTest(
            """<page   route = "/" ><a href = "/x"   data-y="1" >go</a></page>""",
            """<page route="/"><a href="/x" data-y="1">go</a></page>""",
        )
    }

    fun testInlineElementStaysInline() {
        doTest(
            """
            <page route="/">
              <h1>{ user.name }</h1>
            </page>
            """.trimIndent(),
            """
            <page route="/">
              <h1>{ user.name }</h1>
            </page>
            """.trimIndent(),
        )
    }

    fun testScriptServerBodyReindentsPreservingStructure() {
        doTest(
            """
            <page route="/">
            <script server provides="fruits">
                    listOf(
                        "alpha",
                    )
            </script>
            </page>
            """.trimIndent(),
            """
            <page route="/">
              <script server provides="fruits">
                listOf(
                    "alpha",
                )
              </script>
            </page>
            """.trimIndent(),
        )
    }

    fun testStyleBodyTrulyFormatsAsCss() {
        // The body is reformatted by the REAL CSS formatter (default code style: one declaration
        // per line) and re-based one level inside the tag.
        doTest(
            """
            <component tag="card">
            <style scoped>
            .card { color: red; }
              .card:hover { color: blue; }
            </style>
            </component>
            """.trimIndent(),
            """
            <component tag="card">
              <style scoped>
                .card {
                    color: red;
                }

                .card:hover {
                    color: blue;
                }
              </style>
            </component>
            """.trimIndent(),
        )
    }

    fun testElseClauseAlignsWithIf() {
        doTest(
            """
            <page route="/">
            <if user != null>
            <p>hi</p>
            <else>
            <p>bye</p>
            </if>
            </page>
            """.trimIndent(),
            """
            <page route="/">
              <if user != null>
                <p>hi</p>
              <else>
                <p>bye</p>
              </if>
            </page>
            """.trimIndent(),
        )
    }

    fun testSelfClosingAndVoidTags() {
        doTest(
            """
            <component tag="x">
            <prop name="a" type="String"/>
            <br>
            <span>y</span>
            </component>
            """.trimIndent(),
            """
            <component tag="x">
              <prop name="a" type="String"/>
              <br>
              <span>y</span>
            </component>
            """.trimIndent(),
        )
    }

    fun testCommentsAndBlankLinesPreserved() {
        doTest(
            """
            {# the page #}
            <page route="/">
            <h1>a</h1>

            <h2>b</h2>
            </page>
            """.trimIndent(),
            """
            {# the page #}
            <page route="/">
              <h1>a</h1>

              <h2>b</h2>
            </page>
            """.trimIndent(),
        )
    }

    fun testForLoopWithFlowExpression() {
        doTest(
            """
            <page route="/">
            <for item in listOf("a", "b")>
            <li>{ item }</li>
            </for>
            </page>
            """.trimIndent(),
            """
            <page route="/">
              <for item in listOf("a", "b")>
                <li>{ item }</li>
              </for>
            </page>
            """.trimIndent(),
        )
    }

    fun testRealComponentMarkupStableCssConverges() {
        // A real site's profile-card.bml shape: the MARKUP is already house-style and stays untouched;
        // the <style> and <script client> bodies converge to the CSS/TypeScript formatters' styles
        // (then stay stable — doTest asserts idempotency).
        doTest(
            """
            {# The signed-in identity card: who you are, plus sign out. #}
            <component tag="profile-card">
              <prop name="initials" type="String" required/>
              <prop name="name" type="String" required/>

              <div class="profile-card">
                <span class="author-badge">{ initials }</span>
                <button class="signout-btn" type="button" onclick="signOut(event)">Sign out</button>
              </div>

              <style scoped>
                .profile-card { display: flex; gap: 14px; background: var(--snow);
                  border: 1px solid var(--divider); }
                .signout-btn:hover { border-color: var(--rose); color: var(--rose); }
              </style>

              <script client>
                function signOut(ev: Event) {
                  (ev.currentTarget as HTMLButtonElement).disabled = true
                  auth.signOut()
                    .catch((e: unknown) => console.warn("site: sign-out call failed", e))
                    .finally(() => { location.href = "/account" })
                }
              </script>
            </component>
            """.trimIndent(),
            """
            {# The signed-in identity card: who you are, plus sign out. #}
            <component tag="profile-card">
              <prop name="initials" type="String" required/>
              <prop name="name" type="String" required/>

              <div class="profile-card">
                <span class="author-badge">{ initials }</span>
                <button class="signout-btn" type="button" onclick="signOut(event)">Sign out</button>
              </div>

              <style scoped>
                .profile-card {
                    display: flex;
                    gap: 14px;
                    background: var(--snow);
                    border: 1px solid var(--divider);
                }

                .signout-btn:hover {
                    border-color: var(--rose);
                    color: var(--rose);
                }
              </style>

              <script client>
                function signOut(ev: Event) {
                    (ev.currentTarget as HTMLButtonElement).disabled = true
                    auth.signOut()
                        .catch((e: unknown) => console.warn("site: sign-out call failed", e))
                        .finally(() => {
                            location.href = "/account"
                        })
                }
              </script>
            </component>
            """.trimIndent(),
        )
    }

    fun testRangeReformatOnlyTouchesTheRange() {
        // ⌥⌘L with a selection (or "only VCS changed text") formats a RANGE — the blocks must
        // handle a sub-range without touching the rest of the file.
        val before = "<page route=\"/\">\n<h1>a</h1>\n<h2>b</h2>\n</page>"
        myFixture.configureByText(BmlFileType, before)
        val start = before.indexOf("<h1>")
        val end = before.indexOf("</h1>") + "</h1>".length
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformatText(myFixture.file, start, end)
        }
        val text = myFixture.file.text
        assertTrue("the in-range h1 is re-indented:\n$text", text.contains("\n  <h1>a</h1>"))
        assertTrue("the out-of-range h2 is untouched:\n$text", text.contains("\n<h2>b</h2>"))
    }

    fun testUnclosedTagDoesNotExplode() {
        // Recovery only — no exception, and the well-formed parts still normalize.
        myFixture.configureByText(BmlFileType, "<page route=\"/\">\n<div>\n<p>x</p>\n</page>")
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        assertTrue(myFixture.file.text.contains("<p>x</p>"))
    }
}
