package bosca.bml.parser

/**
 * A source span in the original `.bml` file: character offsets into the UTF-16
 * source plus 1-based line/column. Raw-text regions additionally record a
 * separate content span so the compiler can emit source maps back to `.bml`.
 */
data class Span(
    val startOffset: Int,
    val endOffset: Int,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
)

/** Root of a parsed `.bml` file. */
data class Document(val nodes: List<Node>, val span: Span)

/** A node in the BML tree. */
sealed interface Node {
    val span: Span
}

/**
 * A markup element. Special tags handled as elements (page/template/component/
 * prop/slot/data/inject/island and ordinary HTML) are all [ElementNode]s; only the
 * control-flow and raw-text tags get dedicated node types.
 */
data class ElementNode(
    val name: String,
    val namespace: String?,            // e.g. "html" in <html:a>
    val attributes: List<Attribute>,
    val children: List<Node>,
    val selfClosing: Boolean,
    override val span: Span,
) : Node {
    val qualifiedName: String get() = if (namespace != null) "$namespace:$name" else name
}

/** Literal text (backslash escapes decoded; entities passed through). */
data class TextNode(val value: String, override val span: Span) : Node

/** A server-evaluated expression: `{ expr }` (HTML-escaped) or `{@ expr }` (raw). */
data class InterpolationNode(val expression: String, val raw: Boolean, override val span: Span) : Node

/** A comment. [emitted] is true for `<!-- -->` (passed through), false for `{# #}` (build-only). */
data class CommentNode(val text: String, val emitted: Boolean, override val span: Span) : Node

/** A raw-text region whose content is captured verbatim (Kotlin / TypeScript / CSS). */
data class RawTextNode(
    val kind: RawKind,
    val attributes: List<Attribute>,
    val content: String,
    val contentSpan: Span,             // span of the verbatim content (for source maps)
    override val span: Span,
) : Node

enum class RawKind { ServerScript, ClientScript, Contract, Style }

/** `<for binding in iterable> … </for>`. */
data class ForNode(
    val binding: ForBinding,
    val iterable: String,
    val children: List<Node>,
    override val span: Span,
) : Node

/** `item` or `(indexOrKey, item)`. */
data class ForBinding(val item: String, val indexOrKey: String?)

/** `<if> … <else-if> … <else> … </if>` (else-if/else folded into one node). */
data class IfNode(
    val branches: List<ConditionalBranch>,   // first = `if`, rest = `else-if`
    val elseChildren: List<Node>?,           // null when there is no `<else>`
    override val span: Span,
) : Node

data class ConditionalBranch(
    val condition: String,
    val children: List<Node>,
    val span: Span,
)

/** An attribute on an element. */
sealed interface Attribute {
    val name: String
    val span: Span
}

/** `name`, `name="literal"`, or `name="pre-{ expr }"`. [value] == null => boolean bare attribute. */
data class StaticAttribute(
    override val name: String,
    val value: List<AttrPart>?,
    override val span: Span,
) : Attribute

/** `:name="expr"` — value is a server expression. */
data class BoundAttribute(
    override val name: String,
    val expression: String,
    override val span: Span,
) : Attribute

/** `@event="expr"` — a client event binding (e.g. `@click`) to a server-action expression. */
data class EventAttribute(
    val event: String,
    val expression: String,
    override val span: Span,
) : Attribute {
    override val name: String get() = "@$event"
}

/** `{...expr}` — merge a `Map<String, Any?>`. */
data class SpreadAttribute(val expression: String, override val span: Span) : Attribute {
    override val name: String get() = "..."
}

/** A segment of a static attribute value. */
sealed interface AttrPart
data class AttrText(val value: String) : AttrPart
data class AttrInterpolation(val expression: String, val raw: Boolean) : AttrPart
