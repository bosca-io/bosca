package bosca.bible

import kotlinx.serialization.Serializable

/** Chapter text and attribution exposed by a Bible implementation, including USX chapters. */
interface IBibleChapterContent {

    val chapter: IChapter
    val attribution: IBibleAttribution?

    /** Materializes the chapter and attribution in their serializable representations. */
    fun asSerializable(): BibleChapterContent = BibleChapterContent(
        chapter.asSerializable(),
        attribution?.asSerializable(),
    )
}

/** Delivered chapter text and attribution. Locally imported content may have no attribution. */
@Serializable
data class BibleChapterContent(
    override val chapter: Chapter,
    override val attribution: BibleAttribution? = null,
) : IBibleChapterContent
