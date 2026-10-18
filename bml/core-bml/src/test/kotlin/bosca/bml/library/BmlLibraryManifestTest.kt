package bosca.bml.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BmlLibraryManifestTest {
    @Test
    fun `runtime identities distinguish coordinates and tags and are safe across DOM CSS and routes`() {
        val ids = listOf("example:design", "example.design:ui", "example:design-ui", "example-design:ui")
            .flatMap { library -> listOf("button", "ui-button").map { tag -> BmlLibraryArtifacts.componentId(library, tag) } }
        assertEquals(ids.size, ids.distinct().size)
        ids.forEach { assertTrue(Regex("[a-z][a-z0-9-]*").matches(it), it) }
        assertEquals(
            BmlLibraryArtifacts.componentId(manifest.library, button.tag),
            BmlLibraryArtifacts.componentId(manifest.copy(version = "2.0").library, button.tag),
        )
        assertFailsWith<IllegalArgumentException> { BmlLibraryArtifacts.componentId("example:design:1", "button") }
        assertFailsWith<IllegalArgumentException> { BmlLibraryArtifacts.componentId("example:design", "ui:button") }
    }
    private val button = BmlLibraryComponent(
        tag = "button", exported = true, renderer = "example.design.ButtonComponent",
        source = BmlLibrarySource("bml/components/button.bml", 2, 1, "sha256:" + "a".repeat(64)),
        props = listOf(BmlLibraryProp("disabled", "Boolean", true)), hasDefaultSlot = true,
        dependencies = emptyList(), eagerDependencies = emptyList(), styles = "button { color: red; }",
        clientModule = null, hasServerState = false, hasClientBehavior = false,
        hasDeferredIslands = false, hasEagerFeatureFlags = false, renderRevision = "revision",
    )
    private val manifest = BmlLibraryManifest(
        formatVersion = BmlLibraryArtifacts.FORMAT_VERSION,
        library = "example:design", version = "1.0", generatedPackage = "example.design",
        compilerVersion = "test", components = listOf(button),
    )

    @Test
    fun `manifest round trips explicit contracts and source identity`() {
        val encoded = BmlLibraryArtifacts.encode(manifest)
        assertEquals(manifest, BmlLibraryArtifacts.decode(encoded))
        assertTrue("\"formatVersion\": 2" in encoded)
        assertTrue("bml/components/button.bml" in encoded)
    }

    @Test
    fun `unsupported format and malformed source locations fail before use`() {
        assertFailsWith<IllegalArgumentException> { manifest.copy(formatVersion = 1).validate() }
        assertFailsWith<IllegalArgumentException> { manifest.copy(formatVersion = 99).validate() }
        for (source in listOf(
            button.source.copy(entry = "/Users/producer/button.bml"),
            button.source.copy(entry = "bml/../button.bml"),
            button.source.copy(entry = "bml/./button.bml"),
            button.source.copy(entry = "bml\\button.bml"),
            button.source.copy(line = 0),
            button.source.copy(digest = "unknown"),
        )) {
            assertFailsWith<IllegalArgumentException>(source.toString()) {
                manifest.copy(components = listOf(button.copy(source = source))).validate()
            }
        }
    }

    @Test
    fun `duplicate identities and missing dependencies are rejected`() {
        for (components in listOf(
            listOf(button, button),
            listOf(button, button.copy(tag = "secondary")),
            listOf(button.copy(dependencies = listOf("missing"))),
            listOf(button.copy(eagerDependencies = listOf("missing"))),
            listOf(button.copy(props = button.props + button.props)),
            listOf(button.copy(renderer = "other.ButtonComponent")),
            listOf(button.copy(tag = "page")),
            listOf(button.copy(dependencies = listOf("button"))),
            listOf(button.copy(exported = false)),
        )) assertFailsWith<IllegalArgumentException> { manifest.copy(components = components).validate() }
    }

    @Test
    fun `names cannot inject Kotlin or replace protected syntax`() {
        for (name in listOf("", "foo..bar", "foo.class", "foo-bar", "foo;import bar", "foo._")) {
            assertFalse(BmlLibraryArtifacts.isPackageName(name), name)
        }
        assertTrue(BmlLibraryArtifacts.isPackageName("bosca.design.generated"))
        for (id in listOf("design", "io.bosca:design:1", "io/bosca:design", ":design")) {
            assertFalse(BmlLibraryArtifacts.isLibraryId(id), id)
        }
        assertTrue(BmlLibraryArtifacts.isLibraryId("io.bosca:bml-design"))
        assertTrue(BmlLibraryArtifacts.isComponentTag("button"))
        assertTrue(BmlLibraryArtifacts.isComponentTag("card-header"))
        for (tag in listOf("script", "component", "import", "use", "subject", "ui:button", "../button")) {
            assertFalse(BmlLibraryArtifacts.isComponentTag(tag), tag)
        }
    }
}
