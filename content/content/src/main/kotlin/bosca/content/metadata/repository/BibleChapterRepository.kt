package bosca.content.metadata.repository

import bosca.content.metadata.model.BibleChapter
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface BibleChapterRepository {

    @Query("insert into bible_chapters (metadata_id, version, variant, book_usfm, usfm, components, sort) values (:metadataId, :version, :variant, :bookUsfm, :usfm, :components, :sort)")
    suspend fun add(chapter: BibleChapter)
    
    @Query("select metadata_id, metadata_id, version, variant, book_usfm, usfm, null as components, sort from bible_chapters where metadata_id = :id and version = :version and variant = :variant and book_usfm = :usfm order by sort")
    suspend fun getAllByIdAndVariantAndUsfm(id: UUID, version: Int, variant: String, usfm: String): List<BibleChapter>

    /** Finds a chapter only when its owning Bible variant is enabled. */
    @Query("""
        select chapter.* from bible_chapters chapter
        join bibles bible on bible.metadata_id = chapter.metadata_id
            and bible.version = chapter.version and bible.variant = chapter.variant
        where chapter.metadata_id = :id and chapter.version = :version
            and chapter.variant = :variant and chapter.usfm = :usfm and bible.enabled
    """)
    suspend fun getByIdAndVariantAndUsfm(id: UUID, version: Int, variant: String, usfm: String): BibleChapter?
}
