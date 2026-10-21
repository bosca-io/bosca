package bosca.bml.ide

import bosca.bml.library.BmlLibraryArtifacts
import bosca.bml.library.BmlLibraryComponent
import bosca.bml.library.BmlLibraryManifest
import bosca.bml.library.BmlLibraryProp
import bosca.bml.library.BmlLibrarySource
import bosca.bml.project.ContentDigest
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.PsiTestUtil
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Uses the shipped Design Library artifacts, attached as dependencies with no producer project in the fixture. */
class BmlLibraryResolutionTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    private val module: Module get() = myModule
    private val scratch = mutableListOf<File>()
    private val attached = mutableListOf<Pair<Module, Library>>()
    private val addedModules = mutableListOf<Module>()

    override fun runInDispatchThread(): Boolean = true

    override fun tearDown() {
        try {
            WriteAction.run<RuntimeException> {
                attached.groupBy({ it.first }, { it.second }).forEach { (owner, libraries) ->
                    val roots = ModuleRootManager.getInstance(owner).modifiableModel
                    libraries.forEach { roots.moduleLibraryTable.removeLibrary(it) }
                    roots.commit()
                }
                addedModules.forEach { ModuleManager.getInstance(project).disposeModule(it) }
            }
            super.tearDown()
        } finally {
            scratch.forEach(FileUtil::delete)
        }
    }

    fun testRealDesignLibraryBareAndAliasedOpeningClosingAndSelfClosingNavigation() {
        val binary = artifact("binary")
        val sources = artifact("sources")
        attach("design", binary, sources)
        myFixture.addFileToProject("imports.bml", """<import library="io.bosca:bml-design"/>""")
        val file = page("""<import library="io.bosca:bml-design" as="ui"/><page route="/"><button/><button>Save</button><ui:button/><ui:button>Save</ui:button></page>""")
        assertEmpty(BmlComponentDecls.scope(file).problems)
        val tags = Regex("(?<=<)(?:ui:)?button|(?<=</)(?:ui:)?button").findAll(file.text).toList()
        assertEquals(6, tags.size)
        tags.forEach {
            val target = requireNotNull(file.findReferenceAt(it.range.first)?.resolve()) { "${it.value} must navigate" }
            assertNotNull("${it.value} must navigate", target)
            assertEquals("button.bml", target.containingFile.name)
            assertTrue(target.containingFile.virtualFile.url.startsWith(sources.url))
            assertEquals("\"button\"", target.text)
        }
        assertEquals(82, BmlComponentDecls.all(file).size)
    }

    fun testRealDesignTagAndPropCompletionUsesMetadata() {
        attach("design", artifact("binary"), artifact("sources"))
        myFixture.addFileToProject("imports.bml", """<import library="io.bosca:bml-design"/>""")
        myFixture.configureByText("home.bml", """<page route="/"><<caret></page>""")
        val tags = myFixture.completeBasic().orEmpty()
        val designButton = tags.filter { it.lookupString == "button" }
        assertEquals("a component that overrides HTML appears once", 1, designButton.size)
        assertTrue(tags.any { it.lookupString == "page-heading" })
        myFixture.configureByText("home.bml", """<page route="/"><button <caret>/></page>""")
        val props = myFixture.completeBasic().orEmpty().map { it.lookupString }
        assertTrue("public metadata props: $props", props.containsAll(listOf("size", ":size", "loading", ":loading", "primary")))
    }

    fun testEmbeddedKotlinNavigatesToRealDesignViewModelSource() {
        val sources = artifact("sources")
        attach("design", artifact("binary"), sources)
        attach("stdlib", jarRoot(File(com.intellij.util.PathUtil.getJarPathForClass(kotlin.Unit::class.java))))
        myFixture.addFileToProject("Anchor.kt", "package fixture\nclass Anchor")
        val file = page("""<page route="/"><script server>val item = bosca.design.model.NavigationItem("Main", "/", false)</script></page>""")
        val host = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, BmlInjectionHost::class.java)
            .single { it.node.elementType == BmlElementTypes.SCRIPT_SERVER_HOST }
        val injected = requireNotNull(com.intellij.lang.injection.InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host))
            .single().first
        val target = requireNotNull(BmlFragmentResolver.resolve(host, injected.text, injected.text.indexOf("NavigationItem") + 1))
        assertEquals("NavigationItem.kt", target.containingFile.name)
        assertTrue("navigation must open attached Kotlin source: ${target.containingFile.virtualFile.url}",
            target.containingFile.virtualFile.url.startsWith(sources.url))
    }

    fun testAttachedSourcesDoNotImplicitlyImportAnyExports() {
        attach("design", artifact("binary"), artifact("sources"))
        val file = page("""<page route="/"><page-heading/></page>""")
        assertEmpty(BmlComponentDecls.all(file))
        assertNull(file.findReferenceAt(file.text.indexOf("page-heading"))?.resolve())
    }

    fun testGlobalImportsEditAndRemovalInvalidateBindingsBeforeConsumerCompilation() {
        attach("design", artifact("binary"), artifact("sources"))
        val file = page("""<page route="/"><button/><ui:button/></page>""")
        assertEmpty(BmlComponentDecls.all(file))
        val imports = myFixture.addFileToProject("imports.bml", """<import library="io.bosca:bml-design"/>""")
        assertNotNull(BmlComponentDecls.find(file, "button"))
        replace(imports, """<import library="io.bosca:bml-design" as="ui"/>""")
        assertNull(BmlComponentDecls.find(file, "button"))
        assertNotNull(BmlComponentDecls.find(file, "ui:button"))
        WriteAction.run<RuntimeException> { imports.delete() }
        assertEmpty(BmlComponentDecls.all(file))
    }

    fun testLocalOverrideDoesNotChangeAliasOrNativeEscapes() {
        attach("design", artifact("binary"), artifact("sources"))
        myFixture.addFileToProject("local.bml", """<component tag="button"><prop name="local" type="String"/><html:button/></component>""")
        val file = page("""<import library="io.bosca:bml-design"/><import library="io.bosca:bml-design" as="ui"/><page route="/"><button/><ui:button/><html:button/><svg:button/></page>""")
        assertEquals("local.bml", requireNotNull(BmlComponentDecls.find(file, "button")).containingFile.name)
        assertEquals(listOf("local"), BmlComponentDecls.propsOf(file, "button"))
        assertEquals("button.bml", requireNotNull(BmlComponentDecls.find(file, "ui:button")).containingFile.name)
        listOf("html:button", "svg:button").forEach { assertNull(BmlComponentDecls.find(file, it)) }
    }

    fun testMissingSourcesKeepMetadataAndProvideAttachSourcesRetryMessage() {
        attach("design", artifact("binary"))
        val file = page("""<import library="io.bosca:bml-design"/><page route="/"><button/></page>""")
        val button = BmlComponentDecls.all(file).single { it.tag == "button" }
        assertNull(button.navElement)
        assertTrue(button.props.contains("loading"))
        assertTrue(requireNotNull(button.navigationProblem).contains("Download matching sources with Gradle"))
        val warnings = myFixture.doHighlighting().filter { it.description?.contains("BML sources for io.bosca:bml-design") == true }
        assertEquals("the tag explains how to retry source navigation", 1, warnings.size)
    }

    fun testPrivateExportsAreNotVisibleEvenWithAttachedSources() {
        val (binary, sources) = fixtureLibrary("test:private", "1", mapOf("public-tag" to true, "private-tag" to false))
        attach("private", binary, sources)
        val file = page("""<import library="test:private"/><page route="/"><private-tag/></page>""")
        assertEquals(listOf("public-tag"), BmlComponentDecls.all(file).map { it.tag })
        assertNull(BmlComponentDecls.find(file, "private-tag"))
    }

    fun testConflictingImportsDoNotChooseAnArbitrarySource() {
        val first = fixtureLibrary("test:first", "1", mapOf("common" to true))
        val second = fixtureLibrary("test:second", "1", mapOf("common" to true))
        attach("first", first.first, first.second)
        attach("second", second.first, second.second)
        val file = page("""<import library="test:first"/><import library="test:second"/><page route="/"><common/></page>""")
        assertEmpty(BmlComponentDecls.all(file))
        val message = BmlComponentDecls.scope(file).problems.single().message
        assertTrue(message, message.contains("Conflicting BML component 'common'"))
        assertNull(BmlComponentDecls.find(file, "common"))
        replace(file, """<import library="test:first" as="one"/><import library="test:second" as="two"/><page route="/"><one:common/><two:common/></page>""")
        assertEmpty(BmlComponentDecls.scope(file).problems)
        assertEquals(setOf("one:common", "two:common"), BmlComponentDecls.all(file).map { it.tag }.toSet())
    }

    fun testDependencyVersionReplacementInvalidatesMetadataAndNavigation() {
        val first = fixtureLibrary("test:versioned", "1", mapOf("old-tag" to true))
        val second = fixtureLibrary("test:versioned", "2", mapOf("new-tag" to true))
        val library = attach("versioned", first.first, first.second)
        val file = page("""<import library="test:versioned"/><page route="/"/>""")
        assertNotNull(BmlComponentDecls.find(file, "old-tag"))
        WriteAction.run<RuntimeException> {
            val model = library.modifiableModel
            model.getUrls(OrderRootType.CLASSES).forEach { model.removeRoot(it, OrderRootType.CLASSES) }
            model.getUrls(OrderRootType.SOURCES).forEach { model.removeRoot(it, OrderRootType.SOURCES) }
            model.addRoot(second.first, OrderRootType.CLASSES)
            model.addRoot(second.second, OrderRootType.SOURCES)
            model.commit()
        }
        assertNull(BmlComponentDecls.find(file, "old-tag"))
        val current = BmlComponentDecls.all(file).single()
        assertEquals("new-tag", current.tag)
        assertTrue(requireNotNull(current.navElement).containingFile.virtualFile.url.startsWith(second.second.url))
        assertEquals("test:versioned:2", current.origin)
    }

    fun testWrongVersionSourcesCannotSatisfyNavigationButCorrectAttachmentCan() {
        val first = fixtureLibrary("test:versioned", "1", mapOf("same-tag" to true))
        val second = fixtureLibrary("test:versioned", "2", mapOf("same-tag" to true))
        val library = attach("versioned", second.first, first.second)
        val file = page("""<import library="test:versioned"/><page route="/"><same-tag/></page>""")
        assertNull(BmlComponentDecls.all(file).single().navElement)
        assertNotNull(BmlComponentDecls.all(file).single().navigationProblem)
        WriteAction.run<RuntimeException> {
            val model = library.modifiableModel
            model.addRoot(second.second, OrderRootType.SOURCES)
            model.commit()
        }
        assertTrue(requireNotNull(BmlComponentDecls.find(file, "same-tag")).containingFile.virtualFile.url.startsWith(second.second.url))
        assertNull(BmlComponentDecls.all(file).single().navigationProblem)
    }

    fun testInvalidImportsUseCompilerValidationAndDoNotExposeBindings() {
        val fixture = fixtureLibrary("test:rules", "1", mapOf("custom-tag" to true))
        attach("rules", fixture.first, fixture.second)
        listOf(
            """<page route="/"><import library="test:rules"/></page>""",
            """<import library="test:rules" as="html"/>""",
            """<import :library='"test:rules"'/>""",
            """<import library="test:rules" unknown="x"/>""",
            """<import library="test:missing"/>""",
        ).forEach { markup ->
            val file = page(markup)
            assertEmpty(BmlComponentDecls.all(file))
            assertFalse("invalid import must explain failure: $markup", BmlComponentDecls.scope(file).problems.isEmpty())
        }
    }

    fun testUnrelatedModuleSourcesImportsAndDependenciesDoNotLeak() {
        val other = otherModule()
        val otherRoot = ModuleRootManager.getInstance(other).sourceRoots.single()
        WriteAction.run<RuntimeException> {
            val component = otherRoot.createChildData(this, "unrelated.bml")
            com.intellij.openapi.vfs.VfsUtil.saveText(component, """<component tag="unrelated-tag"><span/></component>""")
            val imports = otherRoot.createChildData(this, "imports.bml")
            com.intellij.openapi.vfs.VfsUtil.saveText(imports, """<import library="test:unrelated"/>""")
        }
        val fixture = fixtureLibrary("test:unrelated", "1", mapOf("dependency-tag" to true))
        attach("unrelated", fixture.first, fixture.second, other)
        val file = page("""<page route="/"><unrelated-tag/><dependency-tag/></page>""")
        assertEmpty(BmlComponentDecls.all(file))
        replace(file, """<import library="test:unrelated"/><page route="/"><dependency-tag/></page>""")
        assertEmpty(BmlComponentDecls.all(file))
        assertTrue(BmlComponentDecls.scope(file).problems.single().message.contains("not a resolved dependency"))
    }

    fun testProjectDependencyUsesItsOwnOutputManifestAndOriginalSourceRoot() {
        val other = otherModule()
        val root = ModuleRootManager.getInstance(other).sourceRoots.single()
        val fixture = fixtureLibrary("test:project", "1", mapOf("project-tag" to true))
        val outputDir = FileUtil.createTempDirectory("bml-ide-output-", "", true)
        scratch += outputDir
        val output = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(outputDir))
        WriteAction.run<RuntimeException> {
            val source = requireNotNull(fixture.second.findFileByRelativePath("bml/project-tag.bml"))
            com.intellij.openapi.vfs.VfsUtil.saveText(root.createChildData(this, "project-tag.bml"), com.intellij.openapi.vfs.VfsUtilCore.loadText(source))
            val metadata = requireNotNull(fixture.first.findFileByRelativePath(BmlLibraryArtifacts.MANIFEST_PATH))
            val metadataDir = com.intellij.openapi.vfs.VfsUtil.createDirectoryIfMissing(output, "META-INF/bml")
            com.intellij.openapi.vfs.VfsUtil.saveText(metadataDir.createChildData(this, "library-manifest.json"), com.intellij.openapi.vfs.VfsUtilCore.loadText(metadata))
            val otherModel = ModuleRootManager.getInstance(other).modifiableModel
            otherModel.getModuleExtension(CompilerModuleExtension::class.java).apply {
                inheritCompilerOutputPath(false)
                setCompilerOutputPath(output.url)
            }
            otherModel.commit()
            val model = ModuleRootManager.getInstance(module).modifiableModel
            model.addModuleOrderEntry(other)
            model.commit()
        }
        val file = page("""<import library="test:project"/><page route="/"><project-tag/></page>""")
        assertEmpty(BmlComponentDecls.scope(file).problems)
        val target = requireNotNull(BmlComponentDecls.find(file, "project-tag"))
        assertEquals(root.url + "/project-tag.bml", target.containingFile.virtualFile.url)
    }

    fun testGlobalImportValidationExplainsUnboundPageTags() {
        val fixture = fixtureLibrary("test:rules", "1", mapOf("custom-tag" to true))
        attach("rules", fixture.first, fixture.second)
        val imports = myFixture.addFileToProject("imports.bml", """<import library="test:rules" as="html"/>""")
        val file = page("""<page route="/"><div><custom-tag/><span/></div></page>""")
        assertEmpty(BmlComponentDecls.all(file))
        assertEquals(imports, BmlComponentDecls.scope(file).problems.single().file)
        assertEquals("one global error explains the page's bindings", 1,
            myFixture.doHighlighting().count { it.description?.startsWith("imports.bml:") == true })
        replace(imports, """<import library="test:rules"/>""")
        assertNotNull(BmlComponentDecls.find(file, "custom-tag"))
        replace(imports, """<page route="/"/>""")
        assertEmpty(BmlComponentDecls.all(file))
        assertFalse(BmlComponentDecls.scope(file).problems.isEmpty())
    }

    private fun otherModule(): Module {
        val directory = FileUtil.createTempDirectory("bml-ide-other-", "", true)
        scratch += directory
        val root = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory))
        val result = WriteAction.compute<Module, RuntimeException> {
            ModuleManager.getInstance(project).newModule(File(directory, "other.iml").absolutePath, "JAVA_MODULE")
        }
        addedModules += result
        PsiTestUtil.addContentRoot(result, root)
        PsiTestUtil.addSourceRoot(result, root)
        return result
    }

    private fun page(markup: String): PsiFile = myFixture.configureByText("home.bml", markup)

    private fun replace(file: PsiFile, text: String) {
        WriteAction.run<RuntimeException> {
            val document = requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
            document.setText(text)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    private fun artifact(kind: String): VirtualFile {
        val original = File(requireNotNull(System.getProperty("bml.ide.design.$kind")))
        assertTrue("real artifact must exist: $original", original.isFile)
        val copy = FileUtil.createTempFile("bml-design-$kind-", ".jar", true)
        scratch += copy
        original.copyTo(copy, overwrite = true)
        return jarRoot(copy)
    }

    private fun attach(name: String, binary: VirtualFile, sources: VirtualFile? = null, owner: Module = module): Library {
        lateinit var result: Library
        WriteAction.run<RuntimeException> {
            val roots = ModuleRootManager.getInstance(owner).modifiableModel
            result = roots.moduleLibraryTable.createLibrary(name)
            val model = result.modifiableModel
            model.addRoot(binary, OrderRootType.CLASSES)
            sources?.let { model.addRoot(it, OrderRootType.SOURCES) }
            model.commit()
            roots.commit()
        }
        attached += owner to result
        return result
    }

    private fun fixtureLibrary(id: String, version: String, tags: Map<String, Boolean>): Pair<VirtualFile, VirtualFile> {
        val source = tags.keys.associateWith { tag ->
            """<!-- version $version --><component tag="$tag"><prop name="fixture" type="String"/><span/></component>"""
        }
        val pkg = "fixture." + id.substringAfter(':')
        val manifest = BmlLibraryManifest(
            BmlLibraryArtifacts.FORMAT_VERSION, id, version, pkg, "fixture",
            tags.map { (tag, exported) ->
                BmlLibraryComponent(
                    tag, exported, "$pkg.Renderer${tag.replace("-", "")}",
                    BmlLibrarySource("bml/$tag.bml", 1, 1, ContentDigest.sha256(source.getValue(tag))),
                    listOf(BmlLibraryProp("fixture", "String", false)), true, emptyList(), emptyList(), "", null,
                    false, false, false, false, "fixture-$version",
                )
            },
        )
        val binary = archive(mapOf(BmlLibraryArtifacts.MANIFEST_PATH to BmlLibraryArtifacts.encode(manifest)))
        val sources = archive(source.mapKeys { (tag, _) -> "bml/$tag.bml" })
        return binary to sources
    }

    private fun archive(entries: Map<String, String>): VirtualFile {
        val archive = FileUtil.createTempFile("bml-ide-dependency-", ".jar", true)
        scratch += archive
        ZipOutputStream(archive.outputStream()).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return jarRoot(archive)
    }

    private fun jarRoot(file: File): VirtualFile {
        val local = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: error("missing $file")
        return JarFileSystem.getInstance().getJarRootForLocalFile(local) ?: error("not an archive: $file")
    }
}
