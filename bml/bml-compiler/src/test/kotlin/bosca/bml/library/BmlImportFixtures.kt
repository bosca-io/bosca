package bosca.bml.library

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

internal object BmlImportFixtures {
    fun component(tag: String = "button", exported: Boolean = true) = BmlLibraryComponent(
        tag = tag, exported = exported,
        renderer = "example.design.${tag.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }}Component",
        source = BmlLibrarySource("bml/$tag.bml", 1, 1, "sha256:" + "a".repeat(64)),
        props = listOf(BmlLibraryProp("disabled", "Boolean", true)), hasDefaultSlot = true,
        dependencies = emptyList(), eagerDependencies = emptyList(), styles = "button { color: var(--fg-0); }",
        clientModule = "button", hasServerState = false, hasClientBehavior = true,
        hasDeferredIslands = false, hasEagerFeatureFlags = false, renderRevision = "revision",
    )

    fun manifest(
        library: String = "example:design",
        version: String = "1.0",
        packageName: String = "example.design",
        components: List<BmlLibraryComponent> = listOf(component(), component("private-label", false)),
    ) = BmlLibraryManifest(
        BmlLibraryArtifacts.FORMAT_VERSION, library, version, packageName, "test",
        components.map { it.copy(renderer = it.renderer.replace("example.design.", "$packageName.")) },
    )

    fun archive(file: File, contents: Map<String, String>) = file.apply {
        JarOutputStream(outputStream()).use { jar ->
            contents.forEach { (path, content) ->
                jar.putNextEntry(JarEntry(path))
                jar.write(content.toByteArray(Charsets.UTF_8))
                jar.closeEntry()
            }
        }
    }
}
