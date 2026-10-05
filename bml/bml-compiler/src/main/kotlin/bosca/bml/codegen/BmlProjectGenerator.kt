package bosca.bml.codegen

import bosca.bml.parser.AttrText
import bosca.bml.parser.BmlParser
import bosca.bml.parser.Diagnostic
import bosca.bml.parser.Document
import bosca.bml.parser.ElementNode
import bosca.bml.parser.Severity
import bosca.bml.parser.StaticAttribute
import java.io.File

/**
 * Generates Kotlin sources for every `.bml` file under a source root. Used by the
 * Gradle source-gen task: `.bml` in, `.kt` in `build/generated/bml`.
 */
class BmlProjectGenerator(private val packageName: String) {

    data class Output(val written: List<File>, val diagnostics: List<Diagnostic>) {
        val errors: List<Diagnostic> get() = diagnostics.filter { it.severity == Severity.Error }
    }

    fun generateAll(sourceRoot: File, outputRoot: File): Output {
        val written = mutableListOf<File>()
        val diagnostics = mutableListOf<Diagnostic>()
        if (!sourceRoot.exists()) return Output(written, diagnostics)

        data class ParsedSource(
            val relativePath: String,
            val source: String,
            val document: Document,
        )

        val sources = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "bml" }
            .sortedBy { it.invariantSeparatorsPath }
            .map { bml ->
                val rel = bml.relativeTo(sourceRoot).invariantSeparatorsPath
                val source = bml.readText()
                val parsed = BmlParser.parse(source)
                diagnostics += parsed.diagnostics
                ParsedSource(rel, source, parsed.document)
            }
            .toList()

        val componentTags = mutableMapOf<String, String>()
        val componentDeclarations = mutableMapOf<String, ElementNode>()
        for (source in sources) {
            topLevelComponents(source.document).forEach { declaration ->
                componentTag(declaration)?.let { tag ->
                    componentTags[tag] = BmlCodeGenerator.componentObjectName(tag)
                    componentDeclarations[tag] = declaration
                }
            }
        }
        val deferredComponentTags = BmlCodeGenerator.componentsUsingDeferredIslands(componentDeclarations)
        val clientBehaviorComponentTags = BmlCodeGenerator.componentsUsingClientBehavior(componentDeclarations)
        val sharedCacheComponentUsage = BmlCodeGenerator.componentsBlockingSharedCache(componentDeclarations)
        val componentPropTypes = StaticPropCoercion.declaredPropTypes(componentDeclarations)

        for (source in sources) {
            val hasMessage = source.document.nodes.any {
                it is ElementNode && it.name == "message" && it.namespace == null
            }
            val objectName = objectNameFor(source.relativePath, if (hasMessage) "Message" else "Page")
            val result = BmlCodeGenerator(
                packageName,
                objectName,
                source.relativePath,
                components = componentTags,
                sourceRevision = bmlRenderRevision(source.source),
                deferredComponentTags = deferredComponentTags,
                clientBehaviorComponentTags = clientBehaviorComponentTags,
                sessionComponentTags = sharedCacheComponentUsage.requiresSession,
                featureFlagComponentTags = sharedCacheComponentUsage.usesEagerFeatureFlags,
                componentPropTypes = componentPropTypes,
            ).generate(source.document)
            diagnostics += result.diagnostics
            if (result.source.isNotEmpty()) {
                val out = File(outputRoot, "$objectName.kt")
                out.parentFile?.mkdirs()
                out.writeText(result.source)
                written += out
                // `.kt.map` sidecar: generated→`.bml` line map for IDE breakpoints.
                // A derived artifact in the same output dir — not counted as a generated source.
                File(outputRoot, "$objectName.kt.map").writeText(result.sourceMap.toJson())
            }
        }
        return Output(written, diagnostics)
    }

    private fun topLevelComponents(document: Document): List<ElementNode> =
        document.nodes.filterIsInstance<ElementNode>()
            .filter { it.name == "component" && it.namespace == null }

    private fun componentTag(declaration: ElementNode): String? {
        val attribute = declaration.attributes.filterIsInstance<StaticAttribute>()
            .firstOrNull { it.name == "tag" } ?: return null
        val value = attribute.value ?: return null
        if (!value.all { it is AttrText }) return null
        return value.joinToString("") { (it as AttrText).value }
    }

    /**
     * Derive a Kotlin object name from a source-root-relative path, e.g. `welcome.bml` ->
     * `WelcomePage` and `pages/library/index.bml` -> `PagesLibraryIndexPage` (message units take the
     * `Message` suffix instead: `messages/course-welcome.bml` -> `MessagesCourseWelcomeMessage`). Directory
     * segments participate so organizing sources into subdirectories (pages/, components/,
     * layouts/) can never collide two files onto one object (three `index.bml` files used to
     * silently overwrite each other and drop routes).
     */
    fun objectNameFor(relativePath: String, suffix: String = "Page"): String {
        val pascal = relativePath.removeSuffix(".bml")
            .split('/', '\\', '-', '_', '.', ' ')
            .filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        val safe = if (pascal.isEmpty() || !pascal.first().isLetter()) "Bml$pascal" else pascal
        return safe + suffix
    }
}
