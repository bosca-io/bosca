package bosca.git.ui

import java.net.URI
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/** Resolves repository-local Markdown destinations against the document at its selected commit. */
internal fun repositoryMarkdown(
    source: String,
    id: String,
    sha: String,
    document: String,
    cloneUrl: String,
    listHref: String,
): String {
    val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create())
    val tree = Parser.builder().extensions(extensions).build().parse(source)
    val headingIds = java.util.IdentityHashMap<Heading, String>()
    val usedIds = mutableMapOf<String, Int>()
    tree.accept(
        object : AbstractVisitor() {
            override fun visit(heading: Heading) {
                val title = StringBuilder()
                heading.accept(
                    object : AbstractVisitor() {
                        override fun visit(text: Text) {
                            title.append(text.literal)
                        }

                        override fun visit(code: Code) {
                            title.append(code.literal)
                        }
                    }
                )
                val base =
                    title
                        .toString()
                        .lowercase()
                        .replace(Regex("[^\\p{L}\\p{N} _-]"), "")
                        .trim()
                        .replace(Regex("\\s+"), "-")
                val count = usedIds.getOrDefault(base, 0)
                usedIds[base] = count + 1
                headingIds[heading] = if (count == 0) base else "$base-$count"
                visitChildren(heading)
            }

            override fun visit(image: Image) {
                image.destination =
                    markdownDestination(
                        image.destination,
                        true,
                        id,
                        sha,
                        document,
                        cloneUrl,
                        listHref,
                    )
                visitChildren(image)
            }

            override fun visit(link: Link) {
                link.destination =
                    markdownDestination(
                        link.destination,
                        false,
                        id,
                        sha,
                        document,
                        cloneUrl,
                        listHref,
                    )
                visitChildren(link)
            }
        }
    )
    return HtmlRenderer.builder()
        .extensions(extensions)
        .escapeHtml(true)
        .sanitizeUrls(true)
        .attributeProviderFactory {
            org.commonmark.renderer.html.AttributeProvider { node, _, attributes ->
                if (node is Heading) headingIds[node]?.let { attributes["id"] = it }
            }
        }
        .build()
        .render(tree)
}

internal fun markdownDestination(
    destination: String,
    image: Boolean,
    id: String,
    sha: String,
    document: String,
    cloneUrl: String,
    listHref: String,
): String {
    if (destination.startsWith('#') || destination.startsWith("//")) return destination
    val uri = runCatching { URI(destination.replace(" ", "%20")) }.getOrNull() ?: return ""
    if (uri.isAbsolute)
        return if (uri.scheme.lowercase() in setOf("https", "http", "mailto")) destination else ""
    val root =
        if (uri.path.startsWith('/')) uri.path
        else
            "/${document.substringBeforeLast('/', "").let { if (it.isBlank()) "" else "$it/" }}${uri.path}"
    val normalized = URI(null, null, root, null).normalize().path.removePrefix("/")
    if (normalized.startsWith("../") || normalized.isBlank()) return ""
    val fragment = uri.fragment?.let { "#${encode(it)}" }.orEmpty()
    return if (image) rawFileHref(cloneUrl, sha, normalized)
    else
        repositoryHref(id, sha, normalized.substringBeforeLast('/', ""), normalized, listHref) +
            fragment
}

/** UI raw routes use owner/repository coordinates and a commit SHA; the proxy supplies the API base. */
internal fun rawFileHref(cloneUrl: String, sha: String, path: String): String {
    if (sha.isBlank()) return ""
    val coordinates = URI(cloneUrl).rawPath.removeSuffix(".git").trimEnd('/')
        .split('/').takeLast(2).joinToString("/")
    val base = "/$coordinates"
    return "$base/raw/${encode(sha)}/${path.split('/').joinToString("/") { encode(it) }}"
}
