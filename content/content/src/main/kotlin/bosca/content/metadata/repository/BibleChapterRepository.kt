package bosca.content.metadata.repository

import bosca.content.metadata.model.BibleChapter
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.serialization.OffsetDateTime

@Repository
interface BibleChapterRepository {

    @Query("insert into bible_chapters (metadata_id, version, variant, book_usfm, usfm, components, sort) values (:metadataId, :version, :variant, :bookUsfm, :usfm, :components, :sort)")
    suspend fun add(chapter: BibleChapter)
    
    @Query("""
        select metadata_id, version, variant, book_usfm, usfm, null as components, sort, source_id,
            null as attribution, null as source_version, null as verse_count, null as fums_token,
            null as created, null as expires
        from bible_chapters where metadata_id = :id and version = :version and variant = :variant
            and book_usfm = :usfm order by sort
    """)
    suspend fun getAllByIdAndVariantAndUsfm(id: UUID, version: Int, variant: String, usfm: String): List<BibleChapter>

    /** Finds a chapter only when its owning Bible variant is enabled. */
    @Query("""
        select chapter.*
        from bible_chapters chapter
        join bibles bible on bible.metadata_id = chapter.metadata_id
            and bible.version = chapter.version and bible.variant = chapter.variant
        where chapter.metadata_id = :id and chapter.version = :version
            and chapter.variant = :variant and chapter.usfm = :usfm and bible.enabled
    """)
    suspend fun getByIdAndVariantAndUsfm(id: UUID, version: Int, variant: String, usfm: String): BibleChapter?

    /** Reads the response stored on an existing provider-backed chapter. */
    @Query("""
        select chapter.* from bible_chapters chapter
        join bible_providers.sources source on source.id = chapter.source_id
        where chapter.source_id = :sourceId and chapter.usfm = :usfm and chapter.expires > :now
            and chapter.source_version = source.version and source.deleted_at is null
    """)
    suspend fun getContent(sourceId: UUID, usfm: String, now: OffsetDateTime): BibleChapter?

    /** Attaches a source without modifying chapter references. */
    @Query("update bible_chapters set source_id = :sourceId where metadata_id = :id and version = :version and variant = :variant")
    suspend fun setSource(id: UUID, version: Int, variant: String, sourceId: UUID)

    /** Saves a whole response only while its source version is still active. */
    @Query("""
        update bible_chapters chapter set components = :components, attribution = :attribution,
            source_version = :sourceVersion, verse_count = :verseCount, fums_token = :fumsToken,
            created = :created, expires = :expires
        from bible_providers.sources source
        where chapter.source_id = :sourceId and chapter.usfm = :usfm
            and source.id = chapter.source_id and source.version = :sourceVersion and source.deleted_at is null
    """, returnUpdateCount = true)
    suspend fun putContent(chapter: BibleChapter): Int

    /** Clears responses while preserving the source marker and chapter references. */
    @Query("""
        update bible_chapters set components = null, attribution = null, source_version = null,
            verse_count = null, fums_token = null, created = null, expires = null
        where source_id = :sourceId and expires is not null
        returning *
    """)
    suspend fun clearContent(sourceId: UUID): List<BibleChapter>

    /** Clears expired provider text, attribution and tokens; local chapters have no expiry. */
    @Query("""
        update bible_chapters set components = null, attribution = null, source_version = null,
            verse_count = null, fums_token = null, created = null, expires = null
        where expires <= :now
        returning *
    """)
    suspend fun removeExpired(now: OffsetDateTime): List<BibleChapter>

    /** Lists retained responses for one upstream edition across configurations, newest first. */
    @Query("""
        select chapter.* from bible_chapters chapter
        join bible_providers.sources source on source.id = chapter.source_id
        where source.provider = :provider and source.provider_bible_id = :providerBibleId
            and source.kind = 'text' and source.deleted_at is null and chapter.expires > :now
        order by chapter.created desc, chapter.source_id, chapter.usfm
    """)
    suspend fun getByEdition(provider: String, providerBibleId: String, now: OffsetDateTime): List<BibleChapter>

    /** Evicts one complete response without removing the chapter reference. */
    @Query("""
        update bible_chapters set components = null, attribution = null, source_version = null,
            verse_count = null, fums_token = null, created = null, expires = null
        where source_id = :sourceId and usfm = :usfm
    """, returnUpdateCount = true)
    suspend fun removeContent(sourceId: UUID, usfm: String): Int

    /** Returns a token only while its exact source and response are current and unexpired. */
    @Query("""
        select chapter.fums_token from bible_chapters chapter
        join bible_providers.sources source on source.id = chapter.source_id
        where chapter.metadata_id = :id and chapter.version = :version and chapter.variant = :variant
            and chapter.usfm = :usfm and chapter.expires > :now
            and chapter.source_version = source.version and source.deleted_at is null
    """)
    suspend fun getFumsToken(id: UUID, version: Int, variant: String, usfm: String, now: OffsetDateTime): String?
}
