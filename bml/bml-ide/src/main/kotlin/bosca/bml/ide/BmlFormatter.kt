package bosca.bml.ide

import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.formatter.DocumentBasedFormattingModel
import com.intellij.psi.formatter.common.AbstractBlock

/**
 * Reformat for `.bml`: normalizes tag indentation (children one level in), attribute spacing
 * (`name="value"`, one space between attributes, brackets snapped tight), and the embedded
 * `<script server>`/`<script client>`/`<style>` bodies — each body is re-indented to one level
 * inside its tag while PRESERVING the author's relative indentation (the embedded formatters
 * can't be delegated to: their fragments live inside synthetic injection wrappers, so their
 * own indent math would nest bodies several phantom levels deep).
 *
 * The BML PSI is flat (every token a leaf), so the block tree is synthesized here: a stack-based
 * scan matches open/close tags (HTML void tags and `/>` are leaf elements; `<else>`/`<else-if>`
 * are close-tag-less CLAUSES aligned with their `<if>`).
 */
class BmlFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        val settings = formattingContext.codeStyleSettings
        // Document-based (NOT PsiBasedFormattingModel): whitespace here can live INSIDE the flat
        // script/style body tokens, which the PSI model can't replace (and its injection-range
        // "correction" corrupts them); the document model also actually implements
        // shiftIndentInsideRange, which re-bases multi-line embedded bodies.
        return DocumentBasedFormattingModel(BmlFileBlock(file.node, settings), settings, file)
    }
}

// ── segment model (the synthesized tree over the flat token stream) ──────────────────────────

private sealed class Seg
private class LeafSeg(val node: ASTNode) : Seg()
private class BodySeg(val node: ASTNode) : Seg()
private class ClauseSeg(val openTokens: List<ASTNode>, val children: List<Seg>) : Seg()
private class ElementSeg(
    val openTokens: List<ASTNode>,
    val children: List<Seg>,
    val closeTokens: List<ASTNode>,
) : Seg()

/** Matches the flat token stream into elements/clauses/leaves; tolerant of unclosed tags. */
private class SegScanner(fileNode: ASTNode) {
    private val tokens: List<ASTNode> =
        fileNode.getChildren(null).filter { it.elementType != TokenType.WHITE_SPACE }
    private var i = 0

    fun parse(): List<Seg> = parseChildren(stack = emptyList(), inIf = false)

    /**
     * Segments until EOF, a close tag matching a name in [stack] (left unconsumed for the owner),
     * or — when [inIf] — the next `<else>`/`<else-if>` clause head.
     */
    private fun parseChildren(stack: List<String>, inIf: Boolean): List<Seg> {
        val out = mutableListOf<Seg>()
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t.elementType == BmlTokens.ANGLE && t.text == "</" -> {
                    val name = closeTagName()
                    if (name != null && name in stack) return out // owner consumes it
                    // Orphan close tag — keep its tokens as leaves and move on.
                    out += LeafSeg(t); i++
                }
                t.elementType == BmlTokens.ANGLE && t.text == "<" -> {
                    if (inIf && atClauseHead()) return out
                    out += parseElement(stack)
                }
                t.elementType == BmlElementTypes.SCRIPT_SERVER_HOST ||
                    t.elementType == BmlElementTypes.SCRIPT_CLIENT_HOST ||
                    t.elementType == BmlElementTypes.STYLE_HOST -> {
                    out += BodySeg(t); i++
                }
                else -> {
                    out += LeafSeg(t); i++
                }
            }
        }
        return out
    }

    private fun parseElement(stack: List<String>): Seg {
        val open = mutableListOf<ASTNode>()
        open += tokens[i]; i++ // '<'
        var name: String? = null
        var selfClosed = false
        while (i < tokens.size) {
            val t = tokens[i]
            if (name == null && (t.elementType == BmlTokens.TAG_KEYWORD || t.elementType == BmlElementTypes.TAG_NAME_REF)) {
                name = t.text
            }
            if (t.elementType == BmlTokens.ANGLE) {
                when (t.text) {
                    ">" -> { open += t; i++; break }
                    "/>" -> { open += t; i++; selfClosed = true; break }
                    // "<" / "</": the open tag was never closed — recover, leaf element.
                    else -> { selfClosed = true; break }
                }
            } else {
                open += t; i++
            }
        }
        if (selfClosed || name == null || name.lowercase() in VOID_TAGS) {
            return ElementSeg(open, emptyList(), emptyList())
        }

        val innerStack = stack + name
        val children = mutableListOf<Seg>()
        children += parseChildren(innerStack, inIf = name == "if")
        if (name == "if") {
            while (atClauseHead()) {
                val clauseOpen = mutableListOf<ASTNode>()
                clauseOpen += tokens[i]; i++ // '<'
                while (i < tokens.size) {
                    val t = tokens[i]
                    clauseOpen += t; i++
                    if (t.elementType == BmlTokens.ANGLE && (t.text == ">" || t.text == "/>")) break
                }
                children += ClauseSeg(clauseOpen, parseChildren(innerStack, inIf = true))
            }
        }

        val close = mutableListOf<ASTNode>()
        if (i < tokens.size && tokens[i].elementType == BmlTokens.ANGLE && tokens[i].text == "</" &&
            closeTagName() == name
        ) {
            while (i < tokens.size) {
                val t = tokens[i]
                close += t; i++
                if (t.elementType == BmlTokens.ANGLE && t.text == ">") break
            }
        }
        return ElementSeg(open, children, close)
    }

    /** The tag name after a `</` at [i], or null. */
    private fun closeTagName(): String? = nameAfterAngle()

    /** True when [i] sits on the `<` of an `<else>` / `<else-if …>` clause head. */
    private fun atClauseHead(): Boolean {
        val t = tokens.getOrNull(i) ?: return false
        return t.elementType == BmlTokens.ANGLE && t.text == "<" && nameAfterAngle() in SegScanner.CLAUSE_TAGS
    }

    private fun nameAfterAngle(): String? {
        val next = tokens.getOrNull(i + 1) ?: return null
        return if (next.elementType == BmlTokens.TAG_KEYWORD || next.elementType == BmlElementTypes.TAG_NAME_REF) {
            next.text
        } else {
            null
        }
    }

    companion object {
        val CLAUSE_TAGS = setOf("else", "else-if")
        val VOID_TAGS = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "source", "track", "wbr",
        )
    }
}

// ── blocks ────────────────────────────────────────────────────────────────────────────────────

/** What a block is, for spacing decisions. */
private enum class Kind { ANGLE_OPEN, NAME, ATTR, EQ, VALUE, ANGLE_END, TEXTUAL, CHILD }

private abstract class BmlAbstractBlock(
    node: ASTNode,
    private val indent: Indent,
    val kind: Kind,
    protected val settings: CodeStyleSettings,
) : AbstractBlock(node, null, null) {
    override fun getIndent(): Indent = indent
    override fun isLeaf(): Boolean = subBlocks.isEmpty()
    override fun getSpacing(child1: Block?, child2: Block): Spacing? = bmlSpacing(child1, child2, settings)
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes =
        ChildAttributes(Indent.getNormalIndent(), null)
}

/** Spacing shared by every container: tag punctuation snaps tight, markup preserves line breaks. */
private fun bmlSpacing(child1: Block?, child2: Block, settings: CodeStyleSettings): Spacing? {
    val left = (child1 as? BmlAbstractBlock)?.kind
    val right = (child2 as? BmlAbstractBlock)?.kind
    if (left == null || right == null) return null
    val keepBlank = settings.getCommonSettings(BmlLanguage).KEEP_BLANK_LINES_IN_CODE
    return when {
        // `<`/`</` hug the name; `=` hugs both sides; `>`/`/>` hugs the last attribute.
        left == Kind.ANGLE_OPEN || left == Kind.EQ || right == Kind.EQ ->
            Spacing.createSpacing(0, 0, 0, false, 0)
        right == Kind.ANGLE_END -> Spacing.createSpacing(0, 0, 0, false, 0)
        // Inside an open tag: exactly one space between name/attributes (line wraps allowed).
        left in TAG_PART && right in TAG_PART -> Spacing.createSpacing(1, 1, 0, true, 0)
        // Markup flow: inline stays inline (max one space), existing line breaks preserved.
        else -> Spacing.createSpacing(0, 1, 0, true, keepBlank)
    }
}

private val TAG_PART = setOf(Kind.NAME, Kind.ATTR, Kind.VALUE)

/** A single token (or trimmed text run) — the leaves of the model. */
private class BmlLeafBlock(
    node: ASTNode,
    indent: Indent,
    kind: Kind,
    settings: CodeStyleSettings,
    private val range: TextRange = node.textRange,
) : BmlAbstractBlock(node, indent, kind, settings) {
    override fun getTextRange(): TextRange = range
    override fun buildChildren(): List<Block> = emptyList()
}

/** An element (or clause): open-tag tokens, children one level in, close-tag tokens back out. */
private class BmlSegBlock(
    node: ASTNode,
    indent: Indent,
    private val openTokens: List<ASTNode>,
    private val children: List<Seg>,
    private val closeTokens: List<ASTNode>,
    settings: CodeStyleSettings,
    private val range: TextRange,
) : BmlAbstractBlock(node, indent, Kind.CHILD, settings) {
    override fun getTextRange(): TextRange = range
    override fun buildChildren(): List<Block> {
        val blocks = mutableListOf<Block>()
        openTokens.forEach { blocks += tagTokenBlock(it, settings) }
        children.forEach { blocks += segBlocks(it, Indent.getNormalIndent(), settings) }
        closeTokens.forEach { blocks += tagTokenBlock(it, settings) }
        return blocks
    }
}

private class BmlFileBlock(
    private val fileNode: ASTNode,
    settings: CodeStyleSettings,
) : BmlAbstractBlock(fileNode, Indent.getNoneIndent(), Kind.CHILD, settings) {
    override fun getTextRange(): TextRange = fileNode.textRange
    override fun buildChildren(): List<Block> =
        SegScanner(fileNode).parse().flatMap { segBlocks(it, Indent.getNoneIndent(), settings) }
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes =
        ChildAttributes(Indent.getNoneIndent(), null)
}

/** A token of an open/close tag → leaf block with the right [Kind] + indent for wrapped lines. */
private fun tagTokenBlock(node: ASTNode, settings: CodeStyleSettings): Block {
    val kind = when (node.elementType) {
        BmlTokens.ANGLE -> if (node.text == "<" || node.text == "</") Kind.ANGLE_OPEN else Kind.ANGLE_END
        BmlTokens.TAG_KEYWORD, BmlElementTypes.TAG_NAME_REF -> Kind.NAME
        BmlTokens.ATTR_EQ -> Kind.EQ
        BmlElementTypes.ATTR_VALUE_HOST -> Kind.VALUE
        else -> Kind.ATTR // attr names/directives, tag exprs, flow exprs
    }
    // A wrapped attribute line lands at continuation indent; punctuation/name never start lines.
    val indent = if (kind == Kind.ATTR || kind == Kind.VALUE) Indent.getContinuationIndent() else Indent.getNoneIndent()
    return BmlLeafBlock(node, indent, kind, settings)
}

/** The block(s) for a segment — an embedded body expands into one block per content line. */
private fun segBlocks(seg: Seg, indent: Indent, settings: CodeStyleSettings): List<Block> = when (seg) {
    is ElementSeg -> {
        val first = seg.openTokens.first()
        val last = seg.closeTokens.lastOrNull() ?: seg.children.lastNode() ?: seg.openTokens.last()
        listOf(
            BmlSegBlock(
                first, indent, seg.openTokens, seg.children, seg.closeTokens, settings,
                TextRange(first.textRange.startOffset, last.textRange.endOffset),
            ),
        )
    }
    is ClauseSeg -> {
        val first = seg.openTokens.first()
        val last = seg.children.lastNode() ?: seg.openTokens.last()
        listOf(
            // A clause head aligns with its `<if>` (indent NONE within the if block); its children
            // then indent one level like any other element body.
            BmlSegBlock(
                first, Indent.getNoneIndent(), seg.openTokens, seg.children, emptyList(), settings,
                TextRange(first.textRange.startOffset, last.textRange.endOffset),
            ),
        )
    }
    is BodySeg -> {
        // The whole embedded body is ONE leaf block, trimmed of its surrounding whitespace so the
        // gaps before/after stay adjustable. The engine positions the FIRST content line at this
        // block's indent and shifts the interior lines by the same delta
        // (FormattingModel.shiftIndentInsideRange) — base normalized, relative structure preserved.
        val node = seg.node
        val text = node.text
        val trimmed = text.trimEnd()
        val leading = text.length - text.trimStart().length
        if (trimmed.isBlank()) {
            emptyList()
        } else {
            val range = TextRange(node.startOffset + leading, node.startOffset + trimmed.length)
            listOf(BmlLeafBlock(node, Indent.getNormalIndent(), Kind.TEXTUAL, settings, range))
        }
    }
    is LeafSeg -> {
        val node = seg.node
        if (node.elementType == BmlTokens.TEXT) {
            // Trim the text run's trailing whitespace out of the block so the gap before the next
            // tag is adjustable (the run otherwise swallows the newline + indent that precede it).
            val text = node.text
            val trimmed = text.trimEnd()
            if (trimmed.isEmpty()) {
                emptyList()
            } else {
                val range = TextRange(node.startOffset, node.startOffset + trimmed.length)
                listOf(BmlLeafBlock(node, indent, Kind.TEXTUAL, settings, range))
            }
        } else {
            listOf(BmlLeafBlock(node, indent, Kind.TEXTUAL, settings))
        }
    }
}

private fun List<Seg>.lastNode(): ASTNode? = lastOrNull()?.let { seg ->
    when (seg) {
        is ElementSeg -> seg.closeTokens.lastOrNull() ?: seg.children.lastNode() ?: seg.openTokens.last()
        is ClauseSeg -> seg.children.lastNode() ?: seg.openTokens.last()
        is BodySeg -> seg.node
        is LeafSeg -> seg.node
    }
}

