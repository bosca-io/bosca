package bosca.bible.providers.service

import bosca.bible.BibleContentUse
import bosca.bible.BibleAudioContent
import bosca.bible.Chapter
import bosca.bible.providers.model.BibleProviderResponse
import bosca.bible.providers.model.BibleSource
import bosca.serialization.UUID
import bosca.service.Service
import java.io.OutputStream

/** Manages provider integrations attached to existing Bosca Bible variants. */
interface BibleSourceService : Service {

    /** Attaches a source to an existing configuration and exact Bible variant whose metadata has not been deleted. */
    suspend fun add(source: BibleSource): BibleSource

    /** Finds an active source, including sources whose configuration or variant is disabled. */
    suspend fun getById(id: UUID): BibleSource?

    /** Returns a cached active source validated against its provider configuration and checks [use] on every read. */
    suspend fun getForUse(id: UUID, use: BibleContentUse): BibleSource

    /** Finds the active text source for this exact metadata version and variant. */
    suspend fun getText(metadataId: UUID, metadataVersion: Int, variant: String): BibleSource?

    /** Lists active recordings attached to this exact metadata version and variant. */
    suspend fun getAudio(metadataId: UUID, metadataVersion: Int, variant: String): List<BibleSource>

    /** Lists active sources belonging to a configuration. */
    suspend fun getByConfiguration(configurationId: UUID): List<BibleSource>

    /** Fetches a chapter from a source checked with [getForUse]; does not store chapter data. */
    suspend fun getChapter(source: BibleSource, usfm: String): BibleProviderResponse<Chapter>

    /** Downloads a recording from a source checked with [getForUse]; the caller owns [destination]. */
    suspend fun downloadAudioChapter(source: BibleSource, usfm: String, destination: OutputStream): BibleProviderResponse<BibleAudioContent>

    /** Updates descriptions at [BibleSource.version], preserving identity and reviewed uses. */
    suspend fun update(source: BibleSource): BibleSource

    /** Soft-deletes at [expectedVersion]; stale versions fail. */
    suspend fun remove(id: UUID, expectedVersion: Long): BibleSource

    /**
     * Permanently deletes every source attached to a Bible metadata version, including removed sources.
     * Call within the transaction that deletes that version's Bible variants.
     *
     * @return the deleted sources
     */
    suspend fun deleteByBible(metadataId: UUID, metadataVersion: Int): List<BibleSource>

    /**
     * Permanently deletes every source attached to one Bible variant, including removed sources.
     * Call within the transaction that deletes that variant.
     *
     * @return the deleted sources
     */
    suspend fun deleteByVariant(metadataId: UUID, metadataVersion: Int, variant: String): List<BibleSource>
}
