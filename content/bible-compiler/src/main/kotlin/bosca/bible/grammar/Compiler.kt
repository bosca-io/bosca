package bosca.bible.grammar

import bosca.bible.Reference
import bosca.bible.usx.Attributes
import bosca.bible.usx.BookHeader
import bosca.bible.usx.BookIdentification
import bosca.bible.usx.BookIntroduction
import bosca.bible.usx.BookIntroductionEndTitles
import bosca.bible.usx.BookTitle
import bosca.bible.usx.Break
import bosca.bible.usx.Chapter
import bosca.bible.usx.Char
import bosca.bible.usx.CrossReference
import bosca.bible.usx.CrossReferenceChar
import bosca.bible.usx.Figure
import bosca.bible.usx.Footnote
import bosca.bible.usx.FootnoteChar
import bosca.bible.usx.IntroChar
import bosca.bible.usx.Item
import bosca.bible.usx.ItemContainer
import bosca.bible.usx.List
import bosca.bible.usx.ListChar
import bosca.bible.usx.Milestone
import bosca.bible.usx.Paragraph
import bosca.bible.usx.Position
import bosca.bible.usx.Root
import bosca.bible.usx.Row
import bosca.bible.usx.Sidebar
import bosca.bible.usx.Table
import bosca.bible.usx.TableContent
import bosca.bible.usx.Text
import bosca.bible.usx.Usx
import bosca.bible.usx.VerseEnd
import bosca.bible.usx.VerseStart
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.tree.TerminalNode
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.*
import java.util.logging.Logger

private val log = Logger.getLogger("bosca.bible.grammar.Compiler")

object Compiler {

    var traceEnabled = false

    fun compile(path: Path, `in`: String): Usx =
        compile(path, ByteArrayInputStream(`in`.toByteArray(Charsets.UTF_8)))

    fun compile(path: Path, `in`: InputStream): Usx {
        val stream = CharStreams.fromStream(`in`)
        val lexer = USXLexer(stream)
        val tokenStream = CommonTokenStream(lexer)
        val parser = USXParser(tokenStream)
        parser.isTrace = traceEnabled
        parser.addErrorListener(ErrorListener(path))
        val context = parser.usx()
        val usx = Mapper().map(context)
        return usx
    }
}

private class Mapper {

    private val items = Stack<ItemContainer<Item>>()

    private val references = Stack<Reference>()

    private fun ParserRuleContext.add(item: Item) {
        items.peek().add(item)
        children()
    }

    private fun ParserRuleContext.push(item: Item) {
        if (items.isNotEmpty()) {
            items.peek().add(item)
            if (item is ItemContainer<*>) {
                @Suppress("UNCHECKED_CAST")
                items.push(item as ItemContainer<Item>)
            }
        } else if (item is ItemContainer<*>) {
            @Suppress("UNCHECKED_CAST")
            items.push(item as ItemContainer<Item>)
        } else {
            error("Root item must be a container")
        }
        children()
    }

    private fun ParserRuleContext.children() {
        for (i in 0 until childCount) {
            val child = getChild(i)
            if (child is ParserRuleContext && child.isAttribute()) continue
            when (child) {
                is USXParser.OpenUsxContext -> child.pushUsx()
                is USXParser.ScriptureContext -> child.pushScripture()
                is USXParser.BookIdentificationContext -> child.pushBookIdentification()
                is USXParser.BookHeadersContext -> child.pushBookHeader()
                is USXParser.BookTitlesContext -> child.pushBookTitle()
                is USXParser.BookIntroductionContext -> child.pushBookIntroduction()
                is USXParser.BookIntroductionEndTitlesContext -> child.pushBookIntroductionEndTitles()
                is USXParser.BookChapterLabelContext -> {}
                is USXParser.CloseBookContext -> pop()
                is USXParser.BreakElementContext -> child.pushBreakElement()
                is USXParser.MalformedChapterContext -> child.pushMalformedChapter()
                is USXParser.ChapterContext -> child.children()
                is USXParser.ChapterStartContext -> child.pushChapterStart()
                is USXParser.ChapterContentContext -> child.pushChapterContent()
                is USXParser.ChapterEndContext -> child.popChapterEnd()
                is USXParser.ParaContext -> child.pushParagraph()
                is USXParser.ParaContentContext -> child.children()
                is USXParser.CloseParaContext -> pop()
                is USXParser.SlashCloseContext -> pop()
                is USXParser.CharElementContext -> child.pushChar()
                is USXParser.CharWithAttribContext -> child.children()
                is USXParser.CharStyleWContext -> child.children()
                is USXParser.CharStyleRbContext -> child.children()
                is USXParser.IntroCharContext -> child.pushIntroChar()
                is USXParser.ListCharContext -> child.pushListChar()
                is USXParser.FootnoteCharContext -> child.pushFootnoteChar()
                is USXParser.CrossRefCharContext -> child.pushCrossRefChar()
                is USXParser.FootnoteVerseContext -> child.pushFootnoteVerse()
                is USXParser.CloseCharContext -> pop()
                is USXParser.VerseContext -> child.children()
                is USXParser.VerseStartContext -> child.pushVerse()
                is USXParser.VerseEndContext -> child.popVerse()
                is USXParser.TextContext -> child.pushText()
                is USXParser.CrossReferenceContext -> child.pushCrossReference()
                is USXParser.CloseNoteContext -> pop()
                is USXParser.ListContext -> child.pushList()
                is USXParser.TableContext -> child.pushTable()
                is USXParser.CloseTableContext -> pop()
                is USXParser.RowContext -> child.pushRow()
                is USXParser.CloseRowContext -> pop()
                is USXParser.TableContentContext -> child.pushCell()
                is USXParser.CloseCellContext -> pop()
                is USXParser.RefElementContext -> child.pushRefElement()
                is USXParser.CloseRefContext -> pop()
                is USXParser.FootnoteContext -> child.pushFootnote()
                is USXParser.SidebarContext -> child.pushSidebar()
                is USXParser.CloseSidebarContext -> pop()
                is USXParser.FigureContext -> child.pushFigure()
                is USXParser.CloseFigureContext -> pop()
                is USXParser.MilestoneContext -> child.pushMilestone()
                is USXParser.CloseUsxContext -> {}
                is TerminalNode -> {}
                else -> log.warning("Unsupported child: ${child.javaClass.simpleName}")
            }
        }
    }

    private fun pop(): ItemContainer<Item>? {
        if (items.isEmpty()) {
            log.warning("No more items to pop")
            return null
        }
        val item = items.pop()
        return item
    }

    fun map(ctx: USXParser.UsxContext): Usx {
        ctx.children()
        val content = items.peek() as Usx
        return content
    }

    private fun ParserRuleContext?.toAttributes(): Attributes {
        if (this == null) return Attributes(emptyMap())
        val map = mutableMapOf<String, String>()
        if (isAttribute()) {
            addToMap(map, this)
            return Attributes(map)
        }
        for (i in 0 until childCount) {
            val child = getChild(i)
            if (child is ParserRuleContext) {
                if (child.isAttribute()) {
                    addToMap(map, child)
                }
            }
        }
        return Attributes(map)
    }

    private fun ParserRuleContext?.isAttribute(): Boolean {
        if (this == null) return false
        if (this.javaClass.simpleName.endsWith("AttrsContext") || this is USXParser.UsxAttributesContext) return true
        return childCount >= 3 && getChild(1).text == "="
    }

    private fun addToMap(map: MutableMap<String, String>, ctx: ParserRuleContext) {
        var i = 0
        while(i < ctx.childCount) {
            val child = ctx.getChild(i)
            if (child is TerminalNode) {
                val next = ctx.getChild(i + 2)
                i += 2
                map[child.text] = next.text.replace("\"", "")
            } else if (child is ParserRuleContext) {
                addToMap(map, child)
            } else {
                log.warning("Unsupported child: ${child.javaClass.simpleName}")
            }
            i++
        }
    }

    private fun USXParser.OpenUsxContext.pushUsx() {
        push(Root(Attributes(emptyMap()), null, Position(start.startIndex, stop.stopIndex)))
    }

    private fun USXParser.ListContext.pushList() {
        push(
            List(
                attributes = listAttrs().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.MalformedChapterContext.pushMalformedChapter() {
        push(
            Chapter(
                reference = Reference(references.peek().bookUsfm + ".1"),
                position = Position(start.startIndex, stop.stopIndex),
                number = "1"
            )
        )
    }

    private fun USXParser.ChapterStartContext.pushChapterStart() {
        val reference = Reference(chapterStartAttrs().attrSid().first().STRING().text.replace("\"", "").replace(" ", ".").replace(":", "."))
        push(
            Chapter(
                reference =  reference,
                position = Position(start.startIndex, stop.stopIndex),
                number = chapterStartAttrs().attrNumber().first().STRING().text.replace("\"", "")
            )
        )
    }

    private fun USXParser.ChapterContentContext.pushChapterContent() {
        children()
    }

    private fun USXParser.ParaContext.pushParagraph() {
        push(
            Paragraph(
                attributes = paraAttrs().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.VerseStartContext.pushVerse() {
        val usfm = verseStartAttrs().attrSid().first()
        val reference = Reference(usfm.STRING().text.replace("\"", "").replace(" ", ".").replace(":", "."))
        references.push(reference)
        items.peek().add(VerseStart(
            attributes = verseStartAttrs().toAttributes(),
            reference = reference,
            position = Position(start.startIndex, stop.stopIndex)
        ))
    }

    private fun USXParser.VerseEndContext.popVerse() {
        if (references.isNotEmpty()) {
            references.pop()
        }
        val reference = Reference(attrEid().STRING().text.replace("\"", "").replace(" ", ".").replace(":", "."))
        items.peek().add(VerseEnd(
            attributes = Attributes(mapOf("EID" to (attrEid()?.STRING()?.text?.replace("\"", "") ?: ""))),
            reference = reference,
            position = Position(start.startIndex, stop.stopIndex)
        ))
    }

    private fun USXParser.TextContext.pushText() {
        val t = Text(Attributes(emptyMap()), if (references.isEmpty()) null else references.peek(), Position(start.startIndex, stop.stopIndex))
        t.text = TEXT().text
        add(t)
    }

    private fun USXParser.CharElementContext.pushChar() {
        val attributes = charAttrs()?.toAttributes()
            ?: charWithAttrib()?.charStyleW()?.charStyleWAttrs()?.toAttributes()
            ?: charWithAttrib()?.charStyleRb()?.charStyleRbAttrs()?.toAttributes()
            ?: Attributes(emptyMap())
        push(
            Char(
                attributes = attributes,
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.IntroCharContext.pushIntroChar() {
        push(
            IntroChar(
                attributes = introCharAttrs().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.ListCharContext.pushListChar() {
        push(
            ListChar(
                attributes = listCharAttrs().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.FootnoteCharContext.pushFootnoteChar() {
        push(
            FootnoteChar(
                attributes = footnoteCharAttrs().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.CrossRefCharContext.pushCrossRefChar() {
        push(
            CrossReferenceChar(
                attributes = crossRefCharAttrs().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.FootnoteVerseContext.pushFootnoteVerse() {
        push(
            Char(
                attributes = attrStyleFv().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.FootnoteContext.pushFootnote() {
        push(
            Footnote(
                attributes = footnoteAttrs().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.CrossReferenceContext.pushCrossReference() {
        push(
            CrossReference(
                attributes = crossRefAttrs().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.RefElementContext.pushRefElement() {
        push(
            bosca.bible.usx.Reference(
                attributes = attrLoc().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.SidebarContext.pushSidebar() {
        push(
            Sidebar(
                attributes = sidebarAttrs().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.FigureContext.pushFigure() {
        push(
            Figure(
                attributes = figureAttrs().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.MilestoneContext.pushMilestone() {
        add(
            Milestone(
                attributes = milestoneAttrs().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.TableContext.pushTable() {
        push(
            Table(
                attributes = attrVid().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.RowContext.pushRow() {
        push(
            Row(
                attributes = attrStyleRow().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.TableContentContext.pushCell() {
        push(
            TableContent(
                attributes = cellAttrs().toAttributes(),
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.ChapterEndContext.popChapterEnd() {
        pop()
    }

    private fun USXParser.BreakElementContext.pushBreakElement() {
        push(
            Break(
                reference = if (references.isEmpty()) null else references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.BookIdentificationContext.pushBookIdentification() {
        val reference = Reference(bookIdentAttrs().attrCodeBook().first().VAL_BOOK_CODE().text.replace("\"", ""))
        references.push(reference)
        push(
            BookIdentification(
                attributes = bookIdentAttrs().toAttributes(),
                reference = reference,
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.BookHeadersContext.pushBookHeader() {
        push(
            BookHeader(
                attributes = attrStyleHeader().toAttributes(),
                reference = references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.BookTitlesContext.pushBookTitle() {
        push(
            BookTitle(
                attributes = attrStyleTitle().toAttributes(),
                reference = references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.BookIntroductionContext.pushBookIntroduction() {
        push(
            BookIntroduction(
                attributes = bookIntroAttrs().toAttributes(),
                reference = references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.BookIntroductionEndTitlesContext.pushBookIntroductionEndTitles() {
        push(
            BookIntroductionEndTitles(
                attributes = attrStyleIntroEnd().toAttributes(),
                reference = references.peek(),
                position = Position(start.startIndex, stop.stopIndex)
            )
        )
    }

    private fun USXParser.ScriptureContext.pushScripture() {
        children()
    }
}


private class ErrorListener(private val path: Path) : BaseErrorListener() {

    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String?,
        e: RecognitionException?
    ) {
        log.severe("SYNTAX ERROR: $path line $line:$charPositionInLine $msg")
        throw CompilerException("$path line $line: $charPositionInLine $msg")
    }
}
