package bosca.content.metadata.repository

import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.BibleChapterAudio
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.json.JsonElement

@Repository
interface BibleChapterRepository {

    @Query("insert into bible_chapters (metadata_id, version, variant, book_usfm, usfm, components, sort) values (:metadataId, :version, :variant, :bookUsfm, :usfm, :components, :sort)")
    suspend fun add(chapter: BibleChapter)
    
    @Query("""
        select metadata_id, version, variant, book_usfm, usfm, null as components, sort, source_id,
            null as attribution, null as source_version, null as verse_count, null as fums_token,
            null as created, null as expires, null as audio
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

    /** Reads recording responses from an existing chapter in an enabled variant, omitting chapter text. */
    @Query("""
        select chapter.metadata_id, chapter.version, chapter.variant, chapter.book_usfm, chapter.usfm,
            null as components, chapter.sort, chapter.source_id, null as attribution,
            null as source_version, null as verse_count, null as fums_token, null as created, null as expires,
            chapter.audio
        from bible_chapters chapter
        join bibles bible on bible.metadata_id = chapter.metadata_id
            and bible.version = chapter.version and bible.variant = chapter.variant
        where chapter.metadata_id = :id and chapter.version = :version
            and chapter.variant = :variant and chapter.usfm = :usfm and bible.enabled
    """)
    suspend fun getAudioChapter(id: UUID, version: Int, variant: String, usfm: String): BibleChapter?

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

    /** Lists retained response sizes for one upstream edition across configurations, newest first, omitting text. */
    @Query("""
        select chapter.metadata_id, chapter.version, chapter.variant, chapter.book_usfm, chapter.usfm,
            null as components, chapter.sort, chapter.source_id, null as attribution, null as source_version,
            chapter.verse_count, null as fums_token, chapter.created, chapter.expires, null as audio
        from bible_chapters chapter
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

    /** Reads retained recordings for one source from the existing chapters, omitting chapter text. */
    @Query("""
        select metadata_id, version, variant, book_usfm, usfm, null as components, sort, source_id,
            null as attribution, null as source_version, null as verse_count, null as fums_token,
            null as created, null as expires, audio
        from bible_chapters
        where audio @> jsonb_build_array(jsonb_build_object('sourceId', :sourceId::text))
    """)
    suspend fun getAudioBySource(sourceId: UUID): List<BibleChapter>

    /** Lists recording responses affected by replacing or deleting a Bible variant, omitting chapter text. */
    @Query("""
        select metadata_id, version, variant, book_usfm, usfm, null as components, sort, source_id,
            null as attribution, null as source_version, null as verse_count, null as fums_token,
            null as created, null as expires, audio
        from bible_chapters
        where metadata_id = :id and (:version::integer is null or version = :version)
            and (:variant::text is null or variant = :variant) and audio is not null
    """)
    suspend fun getAudioByBible(id: UUID, version: Int?, variant: String?): List<BibleChapter>

    /** Lists chapters with retained audio for expiry maintenance, omitting chapter text. */
    @Query("""
        select metadata_id, version, variant, book_usfm, usfm, null as components, sort, source_id,
            null as attribution, null as source_version, null as verse_count, null as fums_token,
            null as created, null as expires, audio
        from bible_chapters
        where audio is not null and audio <> '[]'::jsonb
    """)
    suspend fun getRetainedAudio(): List<BibleChapter>

    /** Lists only the cached recordings belonging to this upstream edition. */
    @Query("""
        select chapter.metadata_id, chapter.version, chapter.variant, chapter.book_usfm, chapter.usfm,
            null as components, chapter.sort, chapter.source_id, null as attribution,
            null as source_version, null as verse_count, null as fums_token, null as created, null as expires,
            retained.audio
        from bible_providers.sources source
        join bible_chapters chapter on chapter.audio is not null
            and chapter.audio @> jsonb_build_array(jsonb_build_object('sourceId', source.id::text))
        cross join lateral (
            select jsonb_agg(cached.value) as audio from jsonb_array_elements(chapter.audio) cached
            where cached.value->>'sourceId' = source.id::text
        ) retained
        where source.provider = :provider and source.provider_bible_id = :providerBibleId
            and source.kind = 'audio' and retained.audio is not null
    """)
    suspend fun getAudioByEdition(provider: String, providerBibleId: String): List<BibleChapter>

    /**
     * Updates audio on an existing chapter while its source and metadata are active and its variant is enabled.
     * Returns zero if the chapter is missing or those conditions no longer match.
     */
    @Query("""
        update bible_chapters chapter set audio = coalesce((
            select jsonb_agg(value) from jsonb_array_elements(chapter.audio)
            where value->>'sourceId' <> :sourceId::text
        ), '[]'::jsonb) || :audio
        from bible_providers.sources source
        where chapter.metadata_id = :id and chapter.version = :version
            and chapter.variant = :variant and chapter.usfm = :usfm
            and source.id = :sourceId and source.version = :sourceVersion
            and source.metadata_id = chapter.metadata_id and source.metadata_version = chapter.version
            and source.variant = chapter.variant and source.kind = 'audio' and source.deleted_at is null
            and exists (select 1 from metadata where id = chapter.metadata_id and not deleted)
            and exists (select 1 from bibles where metadata_id = chapter.metadata_id
                and version = chapter.version and variant = chapter.variant and enabled)
    """, returnUpdateCount = true)
    suspend fun updateAudioContent(id: UUID, version: Int, variant: String, usfm: String, sourceId: UUID, sourceVersion: Long, audio: JsonElement): Int

    /** Removes only this exact response, preserving replacements and every other recording on the chapter. */
    @Query("""
        update bible_chapters set audio = (
            select jsonb_agg(value) from jsonb_array_elements(audio) where value->>'id' <> :id::text
        ) where usfm = :usfm and audio @> jsonb_build_array(jsonb_build_object('id', :id::text))
    """, returnUpdateCount = true)
    suspend fun removeAudioContent(audio: BibleChapterAudio): Int

    /** Reads an exact audio response token only while its source, variant and expiry remain current. */
    @Query("""
        select cached.value->>'fumsToken' from bible_chapters chapter
        cross join lateral jsonb_array_elements(chapter.audio) cached
        join bible_providers.sources source on source.id::text = cached.value->>'sourceId'
        join bibles bible on bible.metadata_id = chapter.metadata_id and bible.version = chapter.version
            and bible.variant = chapter.variant and bible.enabled
        where chapter.metadata_id = :id and chapter.version = :version and chapter.variant = :variant
            and chapter.usfm = :usfm and source.id = :audioId and source.kind = 'audio'
            and source.metadata_id = chapter.metadata_id and source.metadata_version = chapter.version
            and source.variant = chapter.variant and source.deleted_at is null
            and source.version = (cached.value->>'sourceVersion')::bigint
            and (cached.value->>'expires')::timestamptz > :now
    """)
    suspend fun getAudioFumsToken(id: UUID, version: Int, variant: String, usfm: String, audioId: UUID, now: OffsetDateTime): String?
}
