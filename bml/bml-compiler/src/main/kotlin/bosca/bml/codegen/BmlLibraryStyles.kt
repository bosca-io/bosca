package bosca.bml.codegen

import bosca.bml.parser.*
import bosca.bml.project.ContentDigest
import java.io.File
import kotlinx.serialization.json.*

/** Uses the BML parser to prepare CSS for bundling before Kotlin generation and CSS scoping. */
object BmlLibraryStyles {
    /** Writes inline stylesheet inputs with their original source directories and stable locations. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected BML source root and stylesheet input file" }
        val root = File(args[0])
        val entries = buildList {
            if (root.isDirectory) root.walkTopDown().onEnter { it.name != "node_modules" }
                .filter { it.isFile && it.extension == "bml" }.sortedBy { it.path }.forEach { file ->
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    map(BmlParser.parse(file.readText()).document) { node ->
                        add(buildJsonObject {
                            put("key", key(relative, node))
                            put("source", relative)
                            put("digest", ContentDigest.sha256(node.content))
                            put("css", node.content)
                        })
                        node
                    }
                }
        }
        File(args[1]).apply { parentFile.mkdirs(); writeText(JsonArray(entries).toString()) }
    }

    /**
     * Replaces only parsed style bodies; script text and source locations remain unchanged. A missing
     * or stale bundled body is passed to [report] with its style span, and that body is left unchanged.
     */
    fun prepare(document: Document, relative: String, styles: JsonObject, report: (String, Span) -> Unit): Document =
        map(document) { node ->
            val prepared = styles[key(relative, node)]?.jsonObject
            when {
                prepared == null -> {
                    report("Missing bundled library CSS for $relative:${node.span.startLine}; run bmlBundleLibraryStyles", node.span)
                    node
                }
                prepared.getValue("digest").jsonPrimitive.content != ContentDigest.sha256(node.content) -> {
                    report("Stale bundled library CSS for $relative:${node.span.startLine}; run bmlBundleLibraryStyles", node.span)
                    node
                }
                else -> node.copy(content = prepared.getValue("css").jsonPrimitive.content)
            }
        }

    /** The bundled stylesheet entries that belong to the BML file at [relative]. */
    fun forSource(styles: JsonObject, relative: String): Map<String, JsonElement> =
        styles.filterKeys { it.substringBeforeLast(':') == relative }

    private fun key(relative: String, node: RawTextNode): String = "$relative:${node.span.startOffset}"

    private fun map(document: Document, transform: (RawTextNode) -> RawTextNode): Document {
        fun nodes(values: List<Node>): List<Node> = values.map { node ->
            when (node) {
                is RawTextNode -> if (node.kind == RawKind.Style) transform(node) else node
                is ElementNode -> node.copy(children = nodes(node.children))
                is ForNode -> node.copy(children = nodes(node.children))
                is IfNode -> node.copy(branches = node.branches.map { it.copy(children = nodes(it.children)) },
                    elseChildren = node.elseChildren?.let(::nodes))
                else -> node
            }
        }
        return document.copy(nodes = nodes(document.nodes))
    }
}
