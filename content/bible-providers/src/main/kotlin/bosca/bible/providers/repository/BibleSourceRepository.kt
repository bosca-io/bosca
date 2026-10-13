package bosca.bible.providers.repository

import bosca.bible.providers.model.BibleSource
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface BibleSourceRepository {

    /** Persists a source for an exact Bible variant only while its metadata exists and has not been deleted. */
    @Query("""
        insert into bible_providers.sources (id, configuration_id, provider, provider_bible_id, metadata_id,
            metadata_version, variant, kind, name, name_local, abbreviation, attribution,
            allowed_uses, version, created, modified, deleted_at)
        select :id, :configurationId, :provider, :providerBibleId, :metadataId, :metadataVersion, :variant,
            :kind, :name, :nameLocal, :abbreviation, :attribution, :allowedUses, :version,
            :created, :modified, :deletedAt
        where exists (select 1 from public.metadata
            where id = :metadataId and not deleted)
        returning *
    """)
    suspend fun add(source: BibleSource): BibleSource?

    /** Finds a source that has not been explicitly removed. */
    @Query("select * from bible_providers.sources where id = :id and deleted_at is null")
    suspend fun get(id: UUID): BibleSource?

    /** Finds the single active text source for this exact Bible version and variant. */
    @Query("""
        select * from bible_providers.sources where metadata_id = :metadataId
            and metadata_version = :metadataVersion and variant = :variant
            and kind = 'text' and deleted_at is null
    """)
    suspend fun getText(metadataId: UUID, metadataVersion: Int, variant: String): BibleSource?

    /** Lists active recordings attached to this exact Bible version and variant. */
    @Query("""
        select * from bible_providers.sources where metadata_id = :metadataId
            and metadata_version = :metadataVersion and variant = :variant
            and kind = 'audio' and deleted_at is null order by created, id
    """)
    suspend fun getAudio(metadataId: UUID, metadataVersion: Int, variant: String): List<BibleSource>

    /** Lists sources still attached to a provider configuration. */
    @Query("""
        select * from bible_providers.sources where configuration_id = :configurationId
            and deleted_at is null order by created, id
    """)
    suspend fun getByConfiguration(configurationId: UUID): List<BibleSource>

    /** Refreshes descriptive metadata at [BibleSource.version], preserving identity and reviewed uses. */
    @Query("""
        update bible_providers.sources set provider = :provider, name = :name, name_local = :nameLocal,
            abbreviation = :abbreviation, attribution = :attribution,
            version = version + 1, modified = now()
        where id = :id and version = :version and deleted_at is null returning *
    """)
    suspend fun update(source: BibleSource): BibleSource?

    /** Permanently deletes every source, including removed sources, attached to a Bible metadata version. */
    @Query("""
        delete from bible_providers.sources where metadata_id = :metadataId and metadata_version = :metadataVersion
        returning *
    """)
    suspend fun deleteByBible(metadataId: UUID, metadataVersion: Int): List<BibleSource>

    /** Removes a source only when its version still matches the caller's observation. */
    @Query("""
        update bible_providers.sources set deleted_at = now(), modified = now(), version = version + 1
        where id = :id and version = :expectedVersion and deleted_at is null returning *
    """)
    suspend fun remove(id: UUID, expectedVersion: Long): BibleSource?
}
