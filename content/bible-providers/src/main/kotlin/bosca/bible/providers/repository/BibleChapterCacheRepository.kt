package bosca.bible.providers.repository

import bosca.bible.providers.model.BibleChapterCache
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@Repository
interface BibleChapterCacheRepository {

    /** Replaces content, attribution, token, and retention dates as one response. */
    @Query("""
        insert into bible_providers.chapters (source_id, provider, usfm, content, attribution,
            verse_count, fums_token, created, expires)
        values (:sourceId, :provider, :usfm, :content, :attribution, :verseCount, :fumsToken, :created, :expires)
        on conflict (source_id, usfm) do update set provider = excluded.provider, content = excluded.content,
            attribution = excluded.attribution, verse_count = excluded.verse_count,
            fums_token = excluded.fums_token, created = excluded.created, expires = excluded.expires
    """)
    suspend fun put(chapter: BibleChapterCache)

    /** Reads an unexpired response from an attached text source without extending retention. */
    @Query("""
        select chapters.* from bible_providers.chapters chapters
        join bible_providers.sources source on source.id = chapters.source_id
        where chapters.source_id = :sourceId and chapters.usfm = :usfm and chapters.expires > :now
            and source.kind = 'text' and source.deleted_at is null
    """)
    suspend fun get(sourceId: UUID, usfm: String, now: OffsetDateTime): BibleChapterCache?

    /** Finds the token of an unexpired exact-variant response without fetching Scripture. */
    @Query("""
        select chapters.fums_token from bible_providers.chapters chapters
        join bible_providers.sources source on source.id = chapters.source_id
        where source.metadata_id = :metadataId and source.metadata_version = :metadataVersion
            and source.variant = :variant and source.kind = 'text' and source.deleted_at is null
            and chapters.usfm = :usfm and chapters.expires > :now
    """)
    suspend fun getFumsToken(metadataId: UUID, metadataVersion: Int, variant: String, usfm: String, now: OffsetDateTime): String?

    /** Deletes expired responses across all configurations, including removed sources. */
    @Query("delete from bible_providers.chapters where expires <= :now", returnUpdateCount = true)
    suspend fun removeExpired(now: OffsetDateTime): Int

    /** Deletes all responses attached to a refreshed or removed source. */
    @Query("delete from bible_providers.chapters where source_id = :sourceId", returnUpdateCount = true)
    suspend fun removeBySource(sourceId: UUID): Int
}
