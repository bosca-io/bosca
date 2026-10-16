package bosca.bml.ide

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercises the registered builder through the editor, including standard folding actions. */
class BmlFoldingTest : BasePlatformTestCase() {
    fun testNestedTagsCollapseAndExpandIndependently() {
        val source = """
            <page route="/">
              <div>
                <button>
                  Save
                </button>
              </div>
            </page>
        """.trimIndent()
        configure(source)
        assertBodies(source, "page", "div", "button")
        assertTrue(regions().all { it.isExpanded })
        assertTrue(regions().all { it.placeholderText == "…" })

        myFixture.editor.caretModel.moveToOffset(source.indexOf("Save"))
        myFixture.performEditorAction("CollapseRegion")
        assertEquals(listOf(body(source, "button")), regions().filter { !it.isExpanded }.map { range(it) })
        myFixture.performEditorAction("ExpandRegion")
        assertTrue(regions().all { it.isExpanded })
        myFixture.performEditorAction("CollapseAllRegions")
        assertTrue(regions().all { !it.isExpanded })
        myFixture.performEditorAction("ExpandAllRegions")
        assertTrue(regions().all { it.isExpanded })
    }

    fun testLibraryTagSpellingsFoldWithoutResolvingImports() {
        val source = """
            <import library="io.bosca:bml-design"/>
            <import library="io.bosca:bml-design" as="ui"/>
            <button>
              Save
            </button>
            <ui:button>
              Save
            </ui:button>
            <html:button>
              Native
            </html:button>
            <input>
              Component child
            </input>
        """.trimIndent()
        configure(source)
        assertBodies(source, "button", "ui:button", "html:button", "input")
    }

    fun testDeclarationsAndControlFlowWithUnclosedClauses() {
        val source = """
            <component tag="choices">
              <for item in items>
                <if item.active>
                  <span>Active</span>
                <else-if item.pending>
                  <span>Pending</span>
                <else>
                  <span>Other</span>
                </if>
              </for>
            </component>
        """.trimIndent()
        configure(source)
        assertBodies(source, "component", "for", "if")
    }

    fun testEmbeddedBodiesAndTagLikeTextAreOpaque() {
        val source = """
            <component tag="sample">
              <script server>
                val markup = "<fake>\n</fake>"
              </script>
              <script client>
                const markup = `<fake>
                  text
                </fake>`;
              </script>
              <style>
                .sample::after { content: "<fake></fake>"; }
              </style>
              {# <fake>
                </fake> #}
              <!-- <fake>
                </fake> -->
              <div title="<fake>
                </fake>">
                { "<fake>\n</fake>" }
              </div>
            </component>
        """.trimIndent()
        configure(source)
        assertEquals(
            setOf(
                body(source, "component"),
                body(source, "script"),
                body(source, "script", source.indexOf("<script client>")),
                body(source, "style"),
                TextRange(source.indexOf("\">\n", source.indexOf("<div")) + 2, source.indexOf("</div>")),
            ),
            regions().map { range(it) }.toSet(),
        )
    }

    fun testSingleLineSelfClosingAndVoidTagsDoNotCreateBodyFolds() {
        val source = """
            <div>
              <span>Inline</span>
              <button></button>
              <ui:button
                label="Save"/>
              <img src="image.png">
              <html:input type="text">
              <br>
            </div>
        """.trimIndent()
        configure(source)
        assertBodies(source, "div")
    }

    fun testRepeatedTagNamesPairWithTheirNearestOpeningTag() {
        val source = "<div>\n<div>\nNested\n</div>\n</div>"
        configure(source)
        assertEquals(setOf(TextRange(5, 26), TextRange(11, 19)), regions().map { range(it) }.toSet())
    }

    fun testMalformedTagsDoNotCreateCrossingOrUnmatchedFolds() {
        val source = """
            </orphan>
            <section>
              <broken
              <div>
                <span>
                  Text
                </div>
              </span>
            </section>
            <unfinished>
              Text
        """.trimIndent()
        configure(source)
        assertBodies(source, "section", "div")
    }

    fun testEditingClosingTagRemovesAndRestoresFold() {
        val source = "<ui:button>\nSave\n</ui:button>"
        configure(source)
        assertBodies(source, "ui:button")
        val start = source.indexOf("</ui:button>")
        replace(start, source.length, "")
        assertEmpty(regions())
        replace(start, start, "</ui:button>")
        assertBodies(source, "ui:button")
    }

    fun testOriginalBmlInReadOnlyLibrarySourcesJar() {
        val source = "<component tag=\"button\">\n<html:button>\n<slot/>\n</html:button>\n</component>"
        val archive = FileUtil.createTempFile("bml-library-sources", ".jar", true)
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("bml/button.bml"))
            zip.write(source.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val localFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(archive))
        val jarRoot = requireNotNull(JarFileSystem.getInstance().getJarRootForLocalFile(localFile))
        val librarySource = requireNotNull(jarRoot.findFileByRelativePath("bml/button.bml"))
        assertFalse(librarySource.isWritable)
        myFixture.configureFromExistingVirtualFile(librarySource)
        updateFolds()
        assertBodies(source, "component", "html:button")
        myFixture.performEditorAction("CollapseAllRegions")
        assertTrue(regions().all { !it.isExpanded })
        myFixture.performEditorAction("ExpandAllRegions")
        assertTrue(regions().all { it.isExpanded })
    }

    private fun configure(source: String) {
        myFixture.configureByText("folding.bml", source)
        updateFolds()
    }

    private fun replace(start: Int, end: Int, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.replaceString(start, end, text)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        updateFolds()
    }

    private fun updateFolds() {
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)
    }

    private fun regions(): List<FoldRegion> = myFixture.editor.foldingModel.allFoldRegions.toList()

    private fun range(region: FoldRegion): TextRange = TextRange(region.startOffset, region.endOffset)

    private fun body(source: String, name: String, from: Int = 0): TextRange {
        val start = source.indexOf('>', source.indexOf("<$name", from)) + 1
        return TextRange(start, source.indexOf("</$name>", start))
    }

    private fun assertBodies(source: String, vararg names: String) {
        var from = 0
        val expected = names.map { name ->
            val open = source.indexOf("<$name", from)
            from = open + name.length + 1
            body(source, name, open)
        }.toSet()
        assertEquals(expected, regions().map { range(it) }.toSet())
    }
}
