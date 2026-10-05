package bosca.bible.usx

import bosca.bible.IChapter
import bosca.bible.Reference
import bosca.bible.components.IComponent
import bosca.bible.components.filter
import bosca.bible.components.initializeStyles
import bosca.bible.style.StyleRegistry
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach
import kotlin.collections.last
import kotlin.collections.mapOf
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.set
import kotlin.collections.sortWith
import kotlin.collections.toList
import kotlin.collections.toTypedArray
import kotlin.text.split
import kotlin.text.toIntOrNull
import kotlin.text.trim

interface ChapterItem : Item

class ChapterVerse(
    val usfm: String,
    val chapter: String,
    val verse: String,
    val items: List<VerseItems>,
    val raw: String,
) {

    fun toString(context: StringContext = StringContext.default): String {
        var buf = ""
        for (item in this.items) {
            buf += item.toString(context)
        }
        return buf.trim()
    }

    override fun toString() = toString(StringContext.default)
}

class Chapter(
    override val reference: Reference,
    position: Position,
    val number: String,
) : ItemContainer<ChapterItem>(reference, position), IChapter, RootItem {

    private val verseItems = mutableMapOf<String, MutableList<VerseItems>>()
    private var _end: ChapterEnd? = null

    override val htmlClass = "chapter c$number"
    override val htmlAttributes = mapOf(
        "data-usfm" to reference.usfm,
        "data-number" to number,
        *super.htmlAttributes.toList().toTypedArray()
    )

    var end: ChapterEnd
        get() = _end ?: error("Chapter end not defined.")
        set(end) {
            if (_end != null) error("Chapter end already defined.")
            _end = end
        }

    var registry: StyleRegistry? = null

    override operator fun get(reference: Reference): IComponent {
        val components = toComponent(ComponentContext())
        components.initializeStyles(registry ?: error("Registry not initialized."))
        if (this.reference == reference) return components
        return components.filter(reference) ?: error("Chapter does not contain verse.")
    }

    fun addVerseItems(items: List<VerseItems>) {
        items.forEach {
            var current = verseItems[it.reference.usfm]
            if (current == null) {
                current = mutableListOf()
                verseItems[it.reference.usfm] = current
            }
            current.add(it)
        }
    }

    override fun add(item: ChapterItem) {
        if (item is ChapterEnd) return
        super.add(item)
    }

    fun getVerses(book: Book): List<ChapterVerse> {
        val verses = mutableListOf<ChapterVerse>()
        for ((usfm, items) in this.verseItems.entries) {
            val usfmParts = usfm.split('.')
            var raw = ""
            for (item in items) {
                raw += book.getRawContent(item.position)
            }
            verses.add(
                ChapterVerse(
                    usfm,
                    number,
                    usfmParts.last(),
                    items,
                    raw,
                )
            )
        }
        verses.sortWith { a, b ->
            val aChapter = a.chapter.toIntOrNull() ?: 0
            val bChapter = b.chapter.toIntOrNull() ?: 0
            if (aChapter > bChapter) return@sortWith 1
            if (aChapter < bChapter) return@sortWith -1
            val aVerse = a.verse.toIntOrNull() ?: 0
            val bVerse = b.verse.toIntOrNull() ?: 0
            if (aVerse > bVerse) return@sortWith 1
            if (aVerse < bVerse) return@sortWith -1
            0
        }
        return verses
    }
}