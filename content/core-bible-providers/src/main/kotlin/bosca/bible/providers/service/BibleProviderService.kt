package bosca.bible.providers.service

import bosca.bible.BibleAudio
import bosca.bible.providers.model.AddProviderAudioInput
import bosca.bible.providers.model.AddProviderBibleInput
import bosca.bible.providers.model.BibleCatalog
import bosca.bible.providers.model.BibleProviderConfiguration
import bosca.bible.providers.model.BibleProviderConfigurationInput
import bosca.bible.providers.model.BibleSource
import bosca.bible.providers.model.BibleSourceKind
import bosca.content.metadata.model.Bible
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages provider configurations and their sources. GraphQL controllers enforce administrator
 * authorization; credentials are stored through the encrypted platform Configuration service.
 */
interface BibleProviderService : Service {

    /** Lists supported provider keys for configuration selection. */
    fun getProviders(): List<String>

    /** Lists configuration details without returning credentials. */
    suspend fun getConfigurations(): List<BibleProviderConfiguration>

    /** Saves a configuration, preserving its saved API key when the input API key is null. */
    suspend fun saveConfiguration(input: BibleProviderConfigurationInput): BibleProviderConfiguration

    /** Verifies a saved configuration without adding Bibles or downloading chapter content. */
    suspend fun test(configurationId: UUID)

    /** Browses a provider's accessible text Bibles or audio recordings with offset pagination. */
    suspend fun getCatalog(
        configurationId: UUID,
        kind: BibleSourceKind,
        query: String?,
        offset: Int,
        limit: Int,
    ): List<BibleCatalog>

    /** Lists active sources belonging to a configuration, including sources of disabled variants. */
    suspend fun getSources(configurationId: UUID): List<BibleSource>

    /** Lists exact Bosca Bible variants compatible with the selected provider recording. */
    suspend fun getAudioTargets(configurationId: UUID, providerBibleId: String): List<Bible>

    /** Adds a selected text Bible as unpublished content in the existing Bibles collection. */
    suspend fun addBible(input: AddProviderBibleInput): Bible

    /** Attaches a compatible recording with reviewed playback rights to an exact Bible variant. */
    suspend fun addAudio(bible: Bible, input: AddProviderAudioInput): BibleAudio

    /** Refreshes source descriptions, languages, and chapter references without prefetching bodies. */
    suspend fun refresh(sourceId: UUID)

    /** Soft-deletes a source at [expectedVersion] and removes its cached text or audio content. */
    suspend fun removeSource(sourceId: UUID, expectedVersion: Long)

    /**
     * Removes a configuration. Active sources prevent removal unless [purgeSources] is true;
     * callers must explicitly confirm removal of the affected sources at the API boundary.
     */
    suspend fun removeConfiguration(configurationId: UUID, purgeSources: Boolean)
}
