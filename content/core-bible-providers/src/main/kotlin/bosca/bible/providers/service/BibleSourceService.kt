package bosca.bible.providers.service

import bosca.bible.BibleChapterContent
import bosca.bible.BibleContentUse
import bosca.bible.providers.model.BibleSource
import bosca.serialization.UUID
import bosca.service.Service

/** Owns provider source persistence and the lifetime of cached responses attached to each source. */
interface BibleSourceService : Service {

    /** Attaches a source to an existing configuration and exact Bible variant. */
    suspend fun add(source: BibleSource): BibleSource

    /** Finds an active source, including sources whose configuration or variant is disabled. */
    suspend fun getById(id: UUID): BibleSource?

    /** Finds the active text source for this exact metadata version and variant. */
    suspend fun getText(metadataId: UUID, metadataVersion: Int, variant: String): BibleSource?

    /** Lists active recordings attached to this exact metadata version and variant. */
    suspend fun getAudio(metadataId: UUID, metadataVersion: Int, variant: String): List<BibleSource>

    /** Lists active sources belonging to a configuration. */
    suspend fun getByConfiguration(configurationId: UUID): List<BibleSource>

    /**
     * Reads text for this exact variant after checking reviewed [use] and configuration availability.
     * Returns null for a local Bible; removed sources fail. Fetches missing responses on demand and
     * retains attribution and the private token with the cached response without extending its expiry.
     */
    suspend fun getChapterContent(
        metadataId: UUID,
        metadataVersion: Int,
        variant: String,
        usfm: String,
        use: BibleContentUse,
    ): BibleChapterContent?

    /** Removes expired text responses and their tokens, including those of disabled configurations. */
    suspend fun removeExpired(): Int

    /**
     * Refreshes descriptions at [BibleSource.version] and purges cached responses atomically.
     * Preserves identity and reviewed uses; fails when the source was removed or its version changed.
     */
    suspend fun update(source: BibleSource): BibleSource

    /** Soft-deletes at [expectedVersion] and purges cached responses atomically; stale versions fail. */
    suspend fun remove(id: UUID, expectedVersion: Long): BibleSource

    /** Returns the server-only token for an unexpired exact-variant text response, without fetching it. */
    suspend fun getFumsToken(metadataId: UUID, metadataVersion: Int, variant: String, usfm: String): String?
}
