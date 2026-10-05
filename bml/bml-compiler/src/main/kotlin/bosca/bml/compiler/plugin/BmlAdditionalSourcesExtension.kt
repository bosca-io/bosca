package bosca.bml.compiler.plugin

import bosca.bml.codegen.BmlClientCodeGenerator
import bosca.bml.codegen.BmlCodeGenerator
import bosca.bml.codegen.BmlComponentMeta
import bosca.bml.codegen.BmlI18nEntry
import bosca.bml.codegen.BmlMetricsManifest
import bosca.bml.codegen.BmlPageMeta
import bosca.bml.codegen.BmlSourceMap
import bosca.bml.codegen.StaticPropCoercion
import bosca.bml.codegen.I18N_MANIFEST_PATH
import bosca.bml.codegen.buildI18nManifest
import bosca.bml.codegen.bmlRenderRevision
import bosca.bml.codegen.scanFunctionKeys
import bosca.bml.contract.ContractCodeGenerator
import bosca.bml.contract.ContractParser
import bosca.bml.parser.AttrText
import bosca.bml.parser.BmlParser
import bosca.bml.parser.Document
import bosca.bml.parser.ElementNode
import bosca.bml.parser.ForNode
import bosca.bml.parser.IfNode
import bosca.bml.parser.Node
import bosca.bml.parser.RawKind
import bosca.bml.parser.RawTextNode
import bosca.bml.parser.Severity
import bosca.bml.parser.StaticAttribute
import org.jetbrains.kotlin.KtInMemoryTextSourceFile
import org.jetbrains.kotlin.KtSourceFile
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.CollectAdditionalSourceFilesExtension
import java.io.File

/**
 * The heart of the BML compiler plugin: reads every page `.bml` under the configured
 * source roots, lowers it to Kotlin, and contributes the result to the compilation as **in-memory
 * [KtSourceFile]s** ([KtInMemoryTextSourceFile]) — no `.kt` files on disk. Because these go through
 * the full K2 frontend, the embedded Kotlin (`{ expr }`, `<script server>`, `<for>/<if>`) resolves
 * normally. Uses the K2 light-tree-compatible [CollectAdditionalSourceFilesExtension] (Kotlin 2.4.0+).
 */
class BmlAdditionalSourcesExtension : CollectAdditionalSourceFilesExtension() {

    override fun isApplicable(configuration: CompilerConfiguration): Boolean =
        configuration.getList(BmlConfigKeys.SOURCE_ROOTS).isNotEmpty()

    override fun collectSources(
        environment: Any,
        configuration: CompilerConfiguration,
        findVirtualFile: (File) -> VirtualFile?,
        sources: Iterable<KtSourceFile>,
    ): Iterable<KtSourceFile> {
        val generated = mutableListOf<KtSourceFile>()
        val pageObjects = mutableListOf<String>() // collected to emit the BmlPages registry
        val messageObjects = mutableListOf<String>() // collected to emit the BmlMessages registry
        val messageEntries = mutableListOf<MessageEntry>()
        val pageMetas = mutableListOf<BmlPageMeta>() // collected to write the site metrics manifest
        val componentMetas = mutableListOf<BmlComponentMeta>()
        // Localized strings harvested from t/t: markup — deduped module-wide (one key,
        // one message; cross-FILE conflicts reported below) — plus static t("…") call keys.
        val i18nByKey = LinkedHashMap<String, BmlI18nEntry>()
        val i18nFunctionKeys = mutableListOf<BmlI18nEntry>()
        val messages = configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY)
        // Client TypeScript (`<script client>` / islands) is emitted to disk for esbuild — TS is not a
        // Kotlin compiler source, so unlike the generated Kotlin it does land in a (build) directory.
        val tsOutputDir = configuration.get(BmlConfigKeys.TS_OUTPUT_DIR)?.let { File(it) }
        // Every compile walks every BML source, so top-level client/contract modules can be rebuilt from
        // scratch. Remove deleted source outputs without touching generator-owned subdirectories such as
        // `graphql/`, which may be produced independently before or alongside this compilation.
        tsOutputDir?.let { dir ->
            dir.listFiles()?.forEach { if (it.isFile && it.extension == "ts") it.delete() }
            dir.mkdirs()
        }
        // When a Kotlin output dir is configured, the generated `.kt` (+ `.kt.map`) is also written to disk
        // and the compiled source is tagged with that real path — so the generated code is readable, errors
        // point at it with correct line numbers, and a debugger can step it. Start clean so removed `.bml`
        // files don't leave stale generated `.kt` behind.
        val kotlinOutputDir = configuration.get(BmlConfigKeys.KOTLIN_OUTPUT_DIR)?.let { File(it) }
        kotlinOutputDir?.let { dir ->
            dir.listFiles()?.forEach { if (it.extension == "kt" || it.name.endsWith(".kt.map")) it.delete() }
            dir.mkdirs()
        }
        fun emit(fileName: String, fallbackPath: String, source: String, sourceMap: BmlSourceMap? = null): KtSourceFile {
            val dir = kotlinOutputDir ?: return KtInMemoryTextSourceFile(fileName, fallbackPath, source)
            val ktFile = File(dir, fileName)
            ktFile.parentFile?.mkdirs()
            ktFile.writeText(source)
            sourceMap?.let { File(dir, "$fileName.map").writeText(it.toJson()) }
            return KtInMemoryTextSourceFile(fileName, ktFile.absolutePath, source)
        }

        // Parse every `.bml` under the source roots once.
        val parsedFiles = mutableListOf<ParsedBml>()
        for (root in configuration.getList(BmlConfigKeys.SOURCE_ROOTS)) {
            val dir = File(root)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "bml" }
                .sortedBy { it.invariantSeparatorsPath }
                .forEach { file ->
                    val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                    val parsed = runCatching { BmlParser.parse(text) }.getOrNull() ?: return@forEach
                    val rel = file.relativeTo(dir).invariantSeparatorsPath
                    parsedFiles += ParsedBml(file, rel, parsed.document, bmlRenderRevision(text))
                    // Static t("key") calls (server scripts, interpolations, client TS) feed the
                    // i18n manifest too, so the CLI can create their keys.
                    i18nFunctionKeys += scanFunctionKeys(text, rel)
                }
        }

        // Pass 1: collect every `<component tag="…">` across all files so pages can instantiate
        // components declared in OTHER files (e.g. a shared `card.bml`).
        val componentTags = mutableMapOf<String, String>()
        val componentDeclarations = mutableMapOf<String, ElementNode>()
        for (p in parsedFiles) {
            topLevelComponents(p.document).forEach { decl ->
                componentTag(decl)?.let { tag ->
                    componentTags[tag] = BmlCodeGenerator.componentObjectName(tag)
                    componentDeclarations[tag] = decl
                }
            }
        }
        val deferredComponentTags = BmlCodeGenerator.componentsUsingDeferredIslands(componentDeclarations)
        val clientBehaviorComponentTags = BmlCodeGenerator.componentsUsingClientBehavior(componentDeclarations)
        val sharedCacheComponentUsage = BmlCodeGenerator.componentsBlockingSharedCache(componentDeclarations)
        val componentPropTypes = StaticPropCoercion.declaredPropTypes(componentDeclarations)

        // Pass 2: generate page + component objects, resolving instantiations against `componentTags`.
        for (p in parsedFiles) {
            val hasRoutable = p.document.nodes.any {
                it is ElementNode && it.name in ROUTABLE_TAGS && it.namespace == null
            }
            val hasMessage = p.document.nodes.any { it is ElementNode && it.name == "message" && it.namespace == null }
            val hasComponent = topLevelComponents(p.document).isNotEmpty()
            if (!hasRoutable && !hasMessage && !hasComponent) continue
            val suffix = when {
                hasMessage -> "Message"
                else -> "Page"
            }
            val objectName = objectNameFor(p.rel, suffix)
            val result = BmlCodeGenerator(
                GENERATED_PACKAGE,
                objectName,
                p.rel,
                componentTags,
                sourceRevision = p.revision,
                deferredComponentTags = deferredComponentTags,
                clientBehaviorComponentTags = clientBehaviorComponentTags,
                sessionComponentTags = sharedCacheComponentUsage.requiresSession,
                featureFlagComponentTags = sharedCacheComponentUsage.usesEagerFeatureFlags,
                componentPropTypes = componentPropTypes,
            ).generate(p.document)
            // Surface codegen diagnostics on the real compile (they were previously visible only to
            // direct generator callers): errors FAIL the build — the t/t: validity rules
            // and friends must never be silent.
            for (d in result.diagnostics) {
                val severity = when (d.severity) {
                    Severity.Error -> CompilerMessageSeverity.ERROR
                    Severity.Warning -> CompilerMessageSeverity.WARNING
                }
                messages?.report(
                    severity,
                    "bml: ${d.message}",
                    CompilerMessageLocation.create(p.file.absolutePath, d.span.startLine, d.span.startColumn, null),
                )
            }
            // Merge the file's localized strings module-wide: identical redeclarations dedupe, a
            // cross-file conflict is a compile error (one key, one message).
            for (entry in result.i18n) {
                val existing = i18nByKey[entry.key]
                when {
                    existing == null -> i18nByKey[entry.key] = entry
                    existing.message != entry.message || existing.pluralForms != entry.pluralForms ->
                        messages?.report(
                            CompilerMessageSeverity.ERROR,
                            "bml i18n: key '${entry.key}' has different source text in " +
                                "${existing.file}:${existing.line} and ${entry.file}:${entry.line} — one key, one message",
                            CompilerMessageLocation.create(p.file.absolutePath, entry.line, 1, null),
                        )
                }
            }
            if (result.source.isNotEmpty()) {
                generated += emit("$objectName.kt", p.file.absolutePath, result.source, result.sourceMap)
                result.pageMeta?.let { pageMetas += it }
                componentMetas += result.componentMeta
                if (hasRoutable) pageObjects += objectName
                if (hasMessage) {
                    messageObjects += objectName
                    val messageEl = p.document.nodes.filterIsInstance<ElementNode>()
                        .first { it.name == "message" && it.namespace == null }
                    val supportsEmail = messageEl.children.any {
                        it is ElementNode && it.name == "email" && it.namespace == null
                    }
                    val supportsPush = messageEl.children.any {
                        it is ElementNode && it.name == "push" && it.namespace == null
                    }
                    messageEntries += MessageEntry(
                        BmlCodeGenerator.messageKey(messageEl, p.rel),
                        p.rel,
                        objectName,
                        supportsEmail,
                        supportsPush,
                    )
                }
            }
            // Emit the page's client TypeScript (no-op when it has no <script client>).
            if (tsOutputDir != null) {
                BmlClientCodeGenerator().generate(
                    p.document,
                    hasTransitiveClientRuntime = result.pageMeta?.clientModule != null,
                )?.let { ts ->
                    val tsFile = File(tsOutputDir, "$objectName.ts")
                    tsFile.parentFile?.mkdirs()
                    tsFile.writeText(ts)
                }
            }
            // Emit each `<contract>`'s two generated halves: the Kotlin interface +
            // dispatcher the site implements and wires into its BmlServer, and the typed TS stub
            // page scripts import (`import { Name } from "./Name"`).
            for (decl in p.document.let(::contractDecls)) {
                generated += emit(
                    "${decl.name}Contract.kt",
                    p.file.absolutePath,
                    ContractCodeGenerator.generateServerDispatcher(decl, GENERATED_PACKAGE),
                )
                if (tsOutputDir != null) {
                    val tsFile = File(tsOutputDir, "${decl.name}.ts")
                    tsFile.parentFile?.mkdirs()
                    tsFile.writeText(ContractCodeGenerator.generateTypeScript(decl))
                }
            }
        }
        // Emit a registry of every page so a deployment can register them all on the server without
        // hand-listing (`BmlServer(project, bml.generated.BmlPages.all, …)`).
        if (pageObjects.isNotEmpty()) {
            // Use an absolute path, consistent with the per-page sources (which use the `.bml`'s
            // absolutePath), so this synthetic source has a stable identity for the frontend/IC.
            val registryPath = File(
                File(configuration.getList(BmlConfigKeys.SOURCE_ROOTS).first()),
                "BmlPages.kt",
            ).absolutePath
            generated += emit("BmlPages.kt", registryPath, buildPageRegistry(pageObjects.sorted()))
        }
        if (messageObjects.isNotEmpty()) {
            val registryPath = File(
                File(configuration.getList(BmlConfigKeys.SOURCE_ROOTS).first()),
                "BmlMessages.kt",
            ).absolutePath
            generated += emit("BmlMessages.kt", registryPath, buildMessageRegistry(messageObjects.sorted()))
        }
        configuration.get(BmlConfigKeys.RESOURCES_OUTPUT_DIR)?.let { dir ->
            val manifest = File(dir, bosca.bml.message.BmlMessageArtifacts.MANIFEST_PATH)
            if (messageEntries.isEmpty()) {
                manifest.delete() // removed message units must not leave a stale manifest behind
            } else {
                manifest.parentFile?.mkdirs()
                manifest.writeText(buildMessageManifest(messageEntries.sortedBy { it.key }))
            }
        }
        // Write the module's i18n manifest: every localized string authored in t/t:
        // markup plus every static t("key") call — the compile-side half of the
        // `bosca bml i18n push` contract. Validate authored function defaults before
        // deduplication; otherwise two conflicting `t("key", "default")` calls silently depend on
        // file traversal order. Markup entries win over compatible or bare function keys.
        val functionByKey = LinkedHashMap<String, BmlI18nEntry>()
        for (entry in i18nFunctionKeys) {
            val existing = i18nByKey[entry.key] ?: functionByKey[entry.key]
            val conflicts = entry.message != null && existing != null &&
                (existing.pluralForms.isNotEmpty() || (existing.message != null && existing.message != entry.message))
            if (conflicts) {
                messages?.report(
                    CompilerMessageSeverity.ERROR,
                    "bml i18n: key '${entry.key}' has different source text in " +
                        "${existing.file}:${existing.line} and ${entry.file}:${entry.line} — one key, one message",
                    CompilerMessageLocation.create(entry.file, entry.line, 1, null),
                )
            } else if (entry.key !in i18nByKey) {
                val priorFunction = functionByKey[entry.key]
                if (priorFunction == null || priorFunction.message == null && entry.message != null) {
                    functionByKey[entry.key] = entry
                }
            }
        }
        configuration.get(BmlConfigKeys.RESOURCES_OUTPUT_DIR)?.let { dir ->
            val manifest = File(dir, I18N_MANIFEST_PATH)
            val all = i18nByKey.values + functionByKey.values
            if (all.isEmpty()) {
                manifest.delete() // removed strings must not leave a stale manifest behind
            } else {
                manifest.parentFile?.mkdirs()
                manifest.writeText(buildI18nManifest(all.sortedBy { it.key }))
            }
        }
        // Emit a registry of every live-island action dispatcher (keyed by state key) so bml-server can
        // resolve POST /_bml/action/{stateKey}. Derived from BmlPages.all (each page exposes its
        // dispatchers), so it needs no dispatcher names here; empty when no page has live islands.
        if (pageObjects.isNotEmpty()) {
            val registryPath = File(
                File(configuration.getList(BmlConfigKeys.SOURCE_ROOTS).first()),
                "BmlIslands.kt",
            ).absolutePath
            generated += emit("BmlIslands.kt", registryPath, buildIslandRegistry(componentTags.isNotEmpty()))
        }
        // Emit a registry of every component (tag -> scope/styles/deps) so the asset pipeline can serve
        // per-component stylesheets and expand a page's render closure (BmlServer(..., BmlComponents.all)).
        if (componentTags.isNotEmpty()) {
            val registryPath = File(
                File(configuration.getList(BmlConfigKeys.SOURCE_ROOTS).first()),
                "BmlComponents.kt",
            ).absolutePath
            generated += emit("BmlComponents.kt", registryPath, buildComponentRegistry(componentTags))
        }
        // Write the site metrics manifest: the per-page asset closure plus one CSS chunk
        // file per styled component, so the Gradle `bmlMetrics` task can report first-load payload
        // sizes as plain file arithmetic. Always rewritten so removed pages/components can't leave
        // stale rows or chunks behind.
        configuration.get(BmlConfigKeys.METRICS_OUTPUT_DIR)?.let { dirPath ->
            val dir = File(dirPath)
            val cssDir = File(dir, BmlMetricsManifest.CSS_DIR)
            cssDir.listFiles()?.forEach { it.delete() }
            dir.mkdirs()
            File(dir, BmlMetricsManifest.FILE_NAME)
                .writeText(BmlMetricsManifest.render(pageMetas, componentMetas))
            componentMetas.filter { it.styles.isNotBlank() }.forEach { meta ->
                cssDir.mkdirs()
                File(cssDir, "${meta.tag}.css").writeText(meta.styles)
            }
        }
        // applyFirProcessSourcesExtension folds our return into the source list. Keep any incoming
        // Kotlin sources, drop the raw `.bml` (they aren't Kotlin), and add the generated Kotlin.
        return sources.filterNot { it.name.endsWith(".bml") } + generated
    }

    /** The `bml.generated.BmlPages` registry listing every generated page object (for server wiring). */
    private fun buildPageRegistry(objectNames: List<String>): String = buildString {
        appendLine("package $GENERATED_PACKAGE")
        appendLine()
        appendLine("import bosca.bml.render.BmlPageRenderer")
        appendLine()
        appendLine("/** All BML pages compiled in this module, for server registration. Generated; do not edit. */")
        appendLine("public object BmlPages {")
        appendLine("    public val all: List<BmlPageRenderer> = listOf(")
        objectNames.forEach { appendLine("        $it,") }
        appendLine("    )")
        appendLine("}")
    }

    /** One compiled message template. */
    private data class MessageEntry(
        val key: String,
        val source: String,
        val objectName: String,
        val supportsEmail: Boolean,
        val supportsPush: Boolean,
    )

    /**
     * The message manifest JSON (`META-INF/bml/message-manifest.json`): the build-time↔host contract —
     * versioned; hosts reject manifest versions they don't understand. `module` is the well-known
     * entry point a runtime instantiates after classloading; the per-template `objectName` and
     * `source` are informational (registry UIs, diagnostics).
     */
    private fun buildMessageManifest(entries: List<MessageEntry>): String = buildString {
        appendLine("{")
        appendLine("  \"manifestVersion\": ${bosca.bml.message.BmlMessageArtifacts.MANIFEST_VERSION},")
        appendLine("  \"module\": \"$GENERATED_PACKAGE.BmlMessages\",")
        appendLine("  \"templates\": [")
        entries.forEachIndexed { i, e ->
            val comma = if (i < entries.size - 1) "," else ""
            appendLine(
                "    {\"key\": ${jsonString(e.key)}, \"source\": ${jsonString(e.source)}, " +
                    "\"objectName\": ${jsonString("$GENERATED_PACKAGE.${e.objectName}")}, " +
                    "\"supportsEmail\": ${e.supportsEmail}, \"supportsPush\": ${e.supportsPush}}$comma",
            )
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** The canonical `bml.generated.BmlMessages` manifest. */
    private fun buildMessageRegistry(objectNames: List<String>): String = buildString {
        appendLine("package $GENERATED_PACKAGE")
        appendLine()
        appendLine("import bosca.bml.message.BmlMessageModule")
        appendLine("import bosca.bml.message.BmlMessageTemplate")
        appendLine()
        appendLine("/** All BML message templates compiled in this module. Generated; do not edit. */")
        appendLine("public object BmlMessages : BmlMessageModule {")
        appendLine("    override val templates: List<BmlMessageTemplate> = listOf(")
        objectNames.forEach { appendLine("        $it,") }
        appendLine("    )")
        appendLine("    public val byKey: Map<String, BmlMessageTemplate> = templates.associateBy { it.key }")
        appendLine("    init {")
        appendLine("        bosca.bml.message.BmlMessageModules.register(this)")
        appendLine("    }")
        appendLine("}")
    }

    /**
     * The `bml.generated.BmlIslands` registry: every live-island action dispatcher keyed by its state key,
     * derived from [BmlPages.all] so it stays in sync without re-listing dispatcher names. Looked up by
     * bml-server at `POST /_bml/action/{stateKey}`.
     */
    private fun buildIslandRegistry(hasComponents: Boolean): String = buildString {
        appendLine("package $GENERATED_PACKAGE")
        appendLine()
        appendLine("import bosca.bml.render.BmlIslandActionDispatcher")
        appendLine()
        appendLine("/** Live-island action dispatchers compiled in this module, keyed by page route then state key")
        appendLine(" *  (state keys are unique per page, not globally), plus component states registered under their")
        appendLine(" *  per-instance \"<tag>.<provides>\" prefix. Generated; do not edit. */")
        appendLine("public object BmlIslands {")
        appendLine("    public val dispatchers: Map<String, Map<String, BmlIslandActionDispatcher>> =")
        appendLine("        BmlPages.all")
        appendLine("            .map { page -> page to (page.islandActionDispatchers + page.deferredRenderers.flatMap { it.islandActionDispatchers }) }")
        appendLine("            .filter { (_, dispatchers) -> dispatchers.isNotEmpty() }")
        appendLine("            .associate { (page, dispatchers) -> page.route to dispatchers.associateBy { it.stateKey } }")
        if (hasComponents) {
            appendLine("    public val componentDispatchers: Map<String, BmlIslandActionDispatcher> =")
            appendLine("        BmlComponents.renderers.values")
            appendLine("            .flatMap { it.islandActionDispatchers + it.deferredRenderers.flatMap { deferred -> deferred.islandActionDispatchers } }")
            appendLine("            .associateBy { it.stateKey }")
        } else {
            appendLine("    public val componentDispatchers: Map<String, BmlIslandActionDispatcher> = emptyMap()")
        }
        appendLine("}")
    }

    /**
     * The `bml.generated.BmlComponents` registry: every component's [bosca.bml.render.BmlComponentInfo]
     * (asset pipeline) plus a tag -> renderer map (sliver re-render). [tagToObject] maps each component
     * tag to its generated object name.
     */
    private fun buildComponentRegistry(tagToObject: Map<String, String>): String = buildString {
        val sortedObjects = tagToObject.values.toSortedSet()
        appendLine("package $GENERATED_PACKAGE")
        appendLine()
        appendLine("import bosca.bml.render.BmlComponentInfo")
        appendLine("import bosca.bml.render.BmlComponentRenderer")
        appendLine()
        appendLine("/** Every BML component compiled in this module: asset metadata + render-by-tag. Generated; do not edit. */")
        appendLine("public object BmlComponents {")
        appendLine("    public val all: List<BmlComponentInfo> = listOf(")
        sortedObjects.forEach { appendLine("        $it.info,") }
        appendLine("    )")
        appendLine("    public val byTag: Map<String, BmlComponentInfo> = all.associateBy { it.tag }")
        appendLine("    public val renderers: Map<String, BmlComponentRenderer> = mapOf(")
        tagToObject.toSortedMap().forEach { (tag, obj) -> appendLine("        \"$tag\" to $obj,") }
        appendLine("    )")
        appendLine("}")
    }

    private class ParsedBml(
        val file: File,
        val rel: String,
        val document: Document,
        val revision: String,
    )

    /** Every `<contract>` region's interface declarations, wherever the region sits in the tree. */
    private fun contractDecls(doc: Document): List<bosca.bml.contract.ContractDecl> {
        val sources = mutableListOf<String>()
        fun walk(nodes: List<Node>) {
            for (n in nodes) when (n) {
                is RawTextNode -> if (n.kind == RawKind.Contract) sources += n.content
                is ElementNode -> walk(n.children)
                is ForNode -> walk(n.children)
                is IfNode -> {
                    n.branches.forEach { walk(it.children) }
                    n.elseChildren?.let { walk(it) }
                }
                else -> Unit
            }
        }
        walk(doc.nodes)
        return sources.flatMap { ContractParser.parse(it) }
    }

    private fun topLevelComponents(doc: Document): List<ElementNode> =
        doc.nodes.filterIsInstance<ElementNode>().filter { it.name == "component" && it.namespace == null }

    /** The literal `tag="…"` of a `<component>` declaration, or null if absent/dynamic. */
    private fun componentTag(decl: ElementNode): String? {
        val attr = decl.attributes.filterIsInstance<StaticAttribute>().firstOrNull { it.name == "tag" } ?: return null
        val v = attr.value ?: return null
        if (!v.all { it is AttrText }) return null
        return v.joinToString("") { (it as AttrText).value }
    }

    /**
     * `welcome.bml` -> `WelcomePage`; `pages/library/index.bml` -> `PagesLibraryIndexPage`; a message
     * unit `messages/course-welcome.bml` -> `MessagesCourseWelcomeMessage`. Directory segments participate
     * (mirrors [bosca.bml.codegen.BmlProjectGenerator.objectNameFor]) so subdirectory organization
     * can never collide two files onto one object.
     */
    private fun objectNameFor(relativePath: String, suffix: String): String {
        val pascal = relativePath.removeSuffix(".bml")
            .split('/', '\\', '-', '_', '.', ' ').filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        val safe = if (pascal.isEmpty() || !pascal.first().isLetter()) "Bml$pascal" else pascal
        return safe + suffix
    }

    private companion object {
        const val GENERATED_PACKAGE = "bml.generated"
        val ROUTABLE_TAGS = setOf("page", "route")
    }
}
