package bosca.content.metadata.repository

import bosca.content.metadata.model.BibleFumsToken
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@Repository
interface BibleFumsTokenRepository {

    /** Stores the newest response token for one source and chapter, replacing an older response's token. */
    @Query("""
        insert into bible_fums_tokens (metadata_id, version, variant, usfm, source_id, audio, token, created, expires)
        values (:metadataId, :version, :variant, :usfm, :sourceId, :audio, :token, :created, :expires)
        on conflict (metadata_id, version, variant, usfm, source_id) do update set
            audio = excluded.audio, token = excluded.token, created = excluded.created, expires = excluded.expires
        where bible_fums_tokens.created <= excluded.created
    """)
    suspend fun put(token: BibleFumsToken)

    /** Reads the newest unexpired text response token for a chapter, whether or not its source or text remain. */
    @Query("""
        select token from bible_fums_tokens
        where metadata_id = :id and version = :version and variant = :variant and usfm = :usfm
            and not audio and expires > :now
        order by created desc limit 1
    """)
    suspend fun getText(id: UUID, version: Int, variant: String, usfm: String, now: OffsetDateTime): String?

    /** Reads the unexpired token for one recording's chapter response, whether or not its source or media remain. */
    @Query("""
        select token from bible_fums_tokens
        where metadata_id = :id and version = :version and variant = :variant and usfm = :usfm
            and source_id = :audioId and audio and expires > :now
    """)
    suspend fun getAudio(id: UUID, version: Int, variant: String, usfm: String, audioId: UUID, now: OffsetDateTime): String?

    /** Deletes tokens whose original response has expired. */
    @Query("delete from bible_fums_tokens where expires <= :now", returnUpdateCount = true)
    suspend fun removeExpired(now: OffsetDateTime): Int
}
