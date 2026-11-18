package bosca.bml.codegen

import bosca.bml.library.BmlImportResolver
import bosca.bml.library.BmlLibraryDependencies
import bosca.bml.parser.AttrText
import bosca.bml.parser.BmlParser
import bosca.bml.parser.Diagnostic
import bosca.bml.parser.Document
import bosca.bml.parser.ElementNode
import bosca.bml.parser.Severity
import bosca.bml.parser.StaticAttribute
import bosca.bml.parser.Span
import java.io.File
import java.io.IOException

/**
 * Generates Kotlin sources for every `.bml` file under a source root. Used by the
 * Gradle source-gen task: `.bml` in, `.kt` in `build/generated/bml`.
 */
class BmlProjectGenerator(private val packageName: String) {

    data class Output(val written: List<File>, val diagnostics: List<Diagnostic>) {
        val errors: List<Diagnostic> get() = diagnostics.filter { it.severity == Severity.Error }
    }

    fun generateAll(sourceRoot: File, outputRoot: File): Output =
        generateAll(sourceRoot, outputRoot, emptyList())

    /** Generates a module using resolved binary libraries and its designated project import file. */
    fun generateAll(
        sourceRoot: File,
        outputRoot: File,
        libraryClasspath: List<File>,
        importsFile: File = File(sourceRoot, "imports.bml"),
    ): Output {
        val written = mutableListOf<File>()
        val diagnostics = mutableListOf<Diagnostic>()
        if (!sourceRoot.exists()) return Output(written, diagnostics)
        File(outputRoot, "BmlProviders.kt").delete()

        data class ParsedSource(
            val file: File,
            val relativePath: String,
            val source: String,
            val document: Document,
        )

        val sources = sourceRoot.walkTopDown()
            .onEnter { it.name != "node_modules" }
            .filter { it.isFile && it.extension == "bml" }
            .sortedBy { it.invariantSeparatorsPath }
            .map { bml ->
                val rel = bml.relativeTo(sourceRoot).invariantSeparatorsPath
                val source = bml.readText()
                val parsed = BmlParser.parse(source)
                diagnostics += parsed.diagnostics
                ParsedSource(bml, rel, source, parsed.document)
            }
            .toMutableList()
        if (importsFile.isFile && sources.none { it.file.canonicalFile == importsFile.canonicalFile }) {
            val source = importsFile.readText()
            val parsed = BmlParser.parse(source)
            diagnostics += parsed.diagnostics
            sources += ParsedSource(importsFile, importsFile.path, source, parsed.document)
        }
        val dependencies = try {
            BmlLibraryDependencies.read(libraryClasspath)
        } catch (failure: IllegalArgumentException) {
            diagnostics += Diagnostic(Severity.Error, failure.message ?: "Invalid BML dependency", sources.firstOrNull()?.document?.span ?: Span(0, 0, 1, 1, 1, 1))
            return Output(written, diagnostics)
        } catch (failure: IOException) {
            diagnostics += Diagnostic(Severity.Error, failure.message ?: "Cannot read BML dependency", sources.firstOrNull()?.document?.span ?: Span(0, 0, 1, 1, 1, 1))
            return Output(written, diagnostics)
        }
        val resolver = BmlImportResolver(dependencies)
        val imports = sources.associateWith { source ->
            BmlImportResolver.parse(
                source.file.path, source.document, source.file.canonicalFile == importsFile.canonicalFile,
            )
        }
        val global = imports.entries.firstOrNull { it.key.file.canonicalFile == importsFile.canonicalFile }?.value

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
        val componentPropTypes = StaticPropCoercion.declaredPropTypes(componentDeclarations)
        val bindings = sources.associateWith { source ->
            val resolved = resolver.resolve(global, imports.getValue(source), componentTags.keys)
            diagnostics += resolved.problems.map { problem ->
                problem.diagnostic.copy(message = "${problem.source}: ${problem.diagnostic.message}")
            }
            resolved.bindings
        }
        if (diagnostics.any { it.severity == Severity.Error }) return Output(written, diagnostics)
        val declarationImports = sources.flatMap { source ->
            topLevelComponents(source.document).mapNotNull { declaration ->
                componentTag(declaration)?.let { it to bindings.getValue(source) }
            }
        }.toMap()
        val capabilities = BmlCodeGenerator.moduleCapabilities(componentDeclarations, declarationImports)

        val hasProviders = dependencies.any { it.manifest.providerRegistrars.isNotEmpty() }
        val usedImports = mutableListOf<BmlImportResolver.Binding>()
        for (source in sources) {
            if (imports.getValue(source) === global) continue
            val hasMessage = source.document.nodes.any {
                it is ElementNode && it.name == "message" && it.namespace == null
            }
            val objectName = objectNameFor(source.relativePath, if (hasMessage) "Message" else "Page")
            val generator = BmlCodeGenerator(
                packageName,
                objectName,
                source.relativePath,
                components = componentTags,
                sourceRevision = bmlRenderRevision(source.source),
                deferredComponentTags = capabilities.deferred,
                clientBehaviorComponentTags = capabilities.clientBehavior,
                sessionComponentTags = capabilities.session,
                featureFlagComponentTags = capabilities.featureFlags,
                componentPropTypes = componentPropTypes,
            ).withImports(bindings.getValue(source))
            if (hasProviders) generator.withLibraryProviders("BmlProviders")
            val document = imports.getValue(source).document
            val result = generator.generate(document)
            usedImports += generator.referencedImports(document)
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
        if (hasProviders && diagnostics.none { it.severity == Severity.Error }) {
            val source = BmlProviderRegistryGenerator.generate(packageName, BmlProviderRegistryGenerator.registrars(usedImports))
            File(outputRoot, "BmlProviders.kt").apply { parentFile.mkdirs(); writeText(source); written += this }
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
