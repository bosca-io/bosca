package bosca.bml.library

import bosca.bml.parser.AttrText
import bosca.bml.parser.CommentNode
import bosca.bml.parser.Diagnostic
import bosca.bml.parser.Document
import bosca.bml.parser.ElementNode
import bosca.bml.parser.ForNode
import bosca.bml.parser.IfNode
import bosca.bml.parser.Node
import bosca.bml.parser.Severity
import bosca.bml.parser.Span
import bosca.bml.parser.StaticAttribute
import bosca.bml.parser.TextNode

/** Shared project/file import binding for compiler, direct generation, and editor consumers. */
class BmlImportResolver(dependencies: List<BmlLibraryDependency>) {
    private val libraries = dependencies.associateBy { it.manifest.library }
    private val graph = BmlLibraryGraph(dependencies)

    init {
        require(libraries.size == dependencies.size) { "Resolve duplicate BML dependencies before binding imports" }
        dependencies.forEach { it.manifest.validate() }
    }

    /** Diagnostics retain their owning source when a module import affects a different source file. */
    data class Problem(val source: String, val diagnostic: Diagnostic)

    /** The original source, its declarations, and markup with top-level import declarations removed. */
    data class ParsedImports(
        val source: String,
        val document: Document,
        val imports: List<BmlImport>,
        val problems: List<Problem>,
    )

    /** Aliased and unprefixed bindings retain the same compiled dependency and component contract. */
    data class Binding @JvmOverloads constructor(
        val declaration: BmlImport,
        val dependency: BmlLibraryDependency,
        val component: BmlLibraryComponent,
        val graph: BmlLibraryGraph = BmlLibraryGraph(listOf(dependency)),
    )

    /** An invalid scope exposes no bindings; diagnostics must be handled before generating code. */
    data class Resolution(val bindings: Map<String, Binding>, val problems: List<Problem>)

    /**
     * Merges module and file declarations without import-order overrides. Exact bindings deduplicate,
     * different owners of one alias or tag fail, and local components shadow unprefixed imports.
     * No dependency source, renderer, or browser asset is evaluated by making its exports visible.
     */
    fun resolve(
        projectImports: ParsedImports?,
        fileImports: ParsedImports,
        localComponents: Set<String> = emptySet(),
    ): Resolution {
        val scopes = listOfNotNull(projectImports, fileImports)
        val problems = scopes.flatMapTo(mutableListOf()) { it.problems }
        val imports = scopes.flatMap { it.imports }.distinctBy { it.library to it.alias }
        val aliases = mutableMapOf<String, BmlImport>()
        val bindings = linkedMapOf<String, Binding>()
        fun conflict(message: String, declaration: BmlImport, previous: BmlImport) {
            problems += Problem(
                declaration.source,
                Diagnostic(
                    Severity.Error,
                    "$message between ${location(previous)} and ${location(declaration)}; use distinct import aliases",
                    declaration.span,
                ),
            )
        }
        for (declaration in imports) {
            val dependency = libraries[declaration.library]
            if (dependency == null) {
                problems += Problem(
                    declaration.source,
                    Diagnostic(
                        Severity.Error,
                        "BML library '${declaration.library}' is not a resolved dependency; declare its binary dependency",
                        declaration.span,
                    ),
                )
                continue
            }
            if (declaration.alias != null) {
                val previous = aliases.putIfAbsent(declaration.alias, declaration)
                if (previous != null && previous.library != declaration.library) {
                    conflict("Conflicting BML import alias '${declaration.alias}'", declaration, previous)
                    continue
                }
            }
            for (component in dependency.manifest.components.filter { it.exported }) {
                val name = declaration.alias?.let { "$it:${component.tag}" } ?: component.tag
                val binding = Binding(declaration, dependency, component, graph)
                val previous = bindings.putIfAbsent(name, binding)
                if (previous != null && previous.dependency.manifest.library != declaration.library) {
                    conflict("Conflicting BML component '$name'", declaration, previous.declaration)
                }
            }
        }
        if (problems.isNotEmpty()) return Resolution(emptyMap(), problems)
        return Resolution(bindings.filterKeys { ':' in it || it !in localComponents }, emptyList())
    }

    companion object {
        private val aliasPattern = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
        private val reservedAliases = setOf("html", "svg", "t")

        /**
         * Extracts literal, top-level imports from a parsed source. A designated module imports file
         * accepts only imports, whitespace, and comments. Imports inside markup or control flow fail.
         * Parser diagnostics remain the caller's responsibility; this pass validates import semantics.
         */
        fun parse(source: String, document: Document, importsOnly: Boolean = false): ParsedImports {
            val imports = mutableListOf<BmlImport>()
            val problems = mutableListOf<Problem>()
            fun error(message: String, span: Span) {
                problems += Problem(source, Diagnostic(Severity.Error, message, span))
            }
            fun nested(nodes: List<Node>) {
                for (node in nodes) {
                    when (node) {
                        is ElementNode -> {
                            if (node.namespace == null && node.name == "import") {
                                error("BML imports must be top-level declarations", node.span)
                            }
                            nested(node.children)
                        }
                        is ForNode -> nested(node.children)
                        is IfNode -> {
                            node.branches.forEach { nested(it.children) }
                            nested(node.elseChildren.orEmpty())
                        }
                        else -> Unit
                    }
                }
            }
            val markup = document.nodes.filterNot { node ->
                if (node is ElementNode && node.namespace == null && node.name == "import") {
                    val startProblems = problems.size
                    if (node.children.any { !isTrivia(it) }) error("BML import declarations cannot have content", node.span)
                    nested(node.children)
                    val attributes = node.attributes
                    attributes.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
                        error("Duplicate BML import attribute '$it'", node.span)
                    }
                    attributes.forEach { attribute ->
                        if (attribute.name !in setOf("library", "as")) {
                            error("Unknown BML import attribute '${attribute.name}'", attribute.span)
                        }
                        if (attribute !is StaticAttribute || attribute.value == null ||
                            attribute.value.any { it !is AttrText }) {
                            error("BML import attributes must have literal values", attribute.span)
                        }
                    }
                    fun literal(name: String): String? = attributes.filterIsInstance<StaticAttribute>()
                        .firstOrNull { it.name == name }?.value
                        ?.takeIf { parts -> parts.all { it is AttrText } }
                        ?.joinToString("") { (it as AttrText).value }
                    val library = literal("library")
                    if (library == null || !BmlLibraryArtifacts.isLibraryId(library)) {
                        error("BML import requires a literal library identity 'group:artifact' without a version", node.span)
                    }
                    val alias = literal("as")
                    if (alias != null && (!aliasPattern.matches(alias) || alias in reservedAliases)) {
                        error("Invalid or reserved BML import alias '$alias'", node.span)
                    }
                    if (problems.size == startProblems && library != null) {
                        imports += BmlImport(library, alias, source, node.span)
                    }
                    true
                } else {
                    if (importsOnly && !isTrivia(node)) {
                        error("Module imports file accepts only imports and comments", node.span)
                    }
                    nested(listOf(node))
                    false
                }
            }
            return ParsedImports(source, document.copy(nodes = markup), imports, problems)
        }

        private fun isTrivia(node: Node): Boolean = node is CommentNode || node is TextNode && node.value.isBlank()

        private fun location(declaration: BmlImport): String =
            "${declaration.source}:${declaration.span.startLine}:${declaration.span.startColumn} (${declaration.library})"
    }
}
