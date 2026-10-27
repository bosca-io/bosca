package bosca.bml.library

import bosca.bml.codegen.BmlCodeGenerator
import bosca.bml.parser.BmlParser
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BmlLibraryGraphTest {
    private val base = BmlImportFixtures.manifest(components = listOf(
        BmlImportFixtures.component().copy(
            dependencies = listOf(id("design", "private-label")), eagerDependencies = emptyList(), hasDeferredIslands = true,
        ),
        BmlImportFixtures.component("private-label", false).copy(hasServerState = true, hasEagerFeatureFlags = true),
        BmlImportFixtures.component("unused"),
    ))
    private val requirement = BmlLibraryRequirement(base.library, base.version, BmlLibraryArtifacts.digest(base), listOf("button"))
    private val wrapper = BmlImportFixtures.manifest("example:wrapper", packageName = "example.wrapper", components = listOf(
        BmlImportFixtures.component("card").copy(dependencies = listOf(id("design", "button")), eagerDependencies = listOf(id("design", "button"))),
    )).copy(requirements = listOf(requirement))

    @Test
    fun `two artifacts cannot claim the same provider registrar`() {
        val declaredBase = base.copy(providerRegistrars = listOf("bosca.di.SharedProviderRegistrar"))
        val declaredWrapper = wrapper.copy(
            providerRegistrars = declaredBase.providerRegistrars,
            requirements = listOf(BmlLibraryRequirement(declaredBase.library, declaredBase.version, BmlLibraryArtifacts.digest(declaredBase), listOf("button"))),
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            BmlLibraryGraph(listOf(dependency(declaredBase), dependency(declaredWrapper)))
        }
        assertTrue("provider registrar" in failure.message.orEmpty(), failure.message)
        assertTrue("design.jar" in failure.message.orEmpty() && "wrapper.jar" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `full and eager closures retain private producer ownership without leaking exports`() {
        val graph = BmlLibraryGraph(listOf(dependency(wrapper), dependency(base)))
        assertEquals(listOf(id("wrapper", "card"), id("design", "button"), id("design", "private-label")),
            graph.closure(wrapper.library, "card").map { it.id })
        assertEquals(listOf(id("wrapper", "card"), id("design", "button")),
            graph.closure(wrapper.library, "card", eager = true).map { it.id })
        val resolver = BmlImportResolver(listOf(dependency(wrapper), dependency(base)))
        val imports = BmlImportResolver.parse("imports.bml", BmlParser.parse("""<import library="example:wrapper"/>""").document, true)
        val bindings = resolver.resolve(null, imports).bindings
        assertEquals(setOf("card"), bindings.keys)
        val document = BmlParser.parse("""<page route="/"><card/></page>""").document
        val generator = BmlCodeGenerator("consumer", "HomePage", "home.bml").withImports(bindings)
        assertEquals(setOf(id("wrapper", "card"), id("design", "button"), id("design", "private-label")),
            generator.importedRenderers(document).keys)
        assertFalse(generator.importedRenderers(document).keys.any { it.endsWith("unused") })
        val shared = BmlParser.parse("""<page route="/" cache="shared"><card/></page>""").document
        assertTrue(BmlCodeGenerator("consumer", "SharedPage", "shared.bml").withImports(bindings).generate(shared)
            .diagnostics.any { "server-session state in its render closure" in it.message })
    }

    @Test
    fun `missing versions changed contracts and unavailable exports retain both artifact contexts`() = temporary { root ->
        val wrapperFile = archive(root, wrapper)
        val missing = assertFailsWith<IllegalArgumentException> { BmlLibraryDependencies.read(listOf(wrapperFile)) }
        assertTrue(wrapperFile.path in missing.message.orEmpty() && base.library in missing.message.orEmpty())
        for ((changed, expected) in listOf(
            base.copy(version = "2.0") to "resolved 2.0",
            base.copy(components = base.components.map { it.copy(renderRevision = "changed") }) to "different compiled contract",
            base.copy(components = base.components.map { if (it.tag == "button") it.copy(exported = false) else it }) to "missing or private",
        )) {
            val baseFile = archive(root, changed)
            val failure = assertFailsWith<IllegalArgumentException> { BmlLibraryDependencies.read(listOf(wrapperFile, baseFile)) }
            assertTrue(expected in failure.message.orEmpty(), failure.message)
            assertTrue(wrapperFile.path in failure.message.orEmpty(), failure.message)
            assertTrue(baseFile.path in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun `renderer ownership cannot overlap even when generated package names differ`() {
        val nested = base.copy(library = "example:nested", generatedPackage = "example.design.nested", components = base.components.map {
            it.copy(renderer = it.renderer.replace("example.design.", "example.design.nested."),
                dependencies = emptyList(), eagerDependencies = emptyList())
        })
        val outer = base.copy(components = nested.components)
        val failure = assertFailsWith<IllegalArgumentException> {
            BmlLibraryGraph(listOf(dependency(outer), dependency(nested)))
        }
        assertTrue("BML renderer" in failure.message.orEmpty(), failure.message)
        assertTrue("design.jar" in failure.message.orEmpty() && "nested.jar" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `library cycles fail before trusting contract digests`() {
        val cyclicBase = base.copy(requirements = listOf(
            BmlLibraryRequirement(wrapper.library, wrapper.version, "sha256:" + "0".repeat(64), listOf("card")),
        ))
        val failure = assertFailsWith<IllegalArgumentException> {
            BmlLibraryGraph(listOf(dependency(wrapper), dependency(cyclicBase)))
        }
        assertTrue("dependency cycle" in failure.message.orEmpty(), failure.message)
        assertTrue("example:wrapper" in failure.message.orEmpty(), failure.message)
        assertTrue("example:design" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a diamond graph deduplicates one private closure across wrapper libraries`() {
        val other = wrapper.copy(library = "example:other", generatedPackage = "example.other", components = wrapper.components.map {
            it.copy(renderer = it.renderer.replace("example.wrapper.", "example.other."))
        })
        val top = BmlImportFixtures.manifest("example:top", packageName = "example.top", components = listOf(
            BmlImportFixtures.component("panel").copy(
                dependencies = listOf(id("wrapper", "card"), id("other", "card")),
                eagerDependencies = listOf(id("wrapper", "card")),
            ),
        )).copy(requirements = listOf(wrapper, other).map {
            BmlLibraryRequirement(it.library, it.version, BmlLibraryArtifacts.digest(it), listOf("card"))
        })
        val graph = BmlLibraryGraph(listOf(top, wrapper, other, base).map(::dependency))
        assertEquals(listOf(base.library, other.library, wrapper.library, top.library), graph.librariesFor(top.library).map { it.manifest.library })
        val closure = graph.closure(top.library, "panel")
        assertEquals(5, closure.size)
        assertEquals(1, closure.count { it.id == id("design", "private-label") })
        assertEquals(3, graph.closure(top.library, "panel", eager = true).size)
    }

    private fun id(library: String, tag: String) = BmlLibraryArtifacts.componentId("example:$library", tag)
    private fun dependency(manifest: BmlLibraryManifest) = BmlLibraryDependency(File("${manifest.library.substringAfter(':')}.jar"), manifest)
    private fun archive(root: File, manifest: BmlLibraryManifest) = BmlImportFixtures.archive(
        File(root, "${manifest.library.substringAfter(':')}.jar"),
        mapOf(BmlLibraryArtifacts.MANIFEST_PATH to BmlLibraryArtifacts.encode(manifest)),
    )
    private fun temporary(block: (File) -> Unit) {
        val root = createTempDirectory("bml-library-graph").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
