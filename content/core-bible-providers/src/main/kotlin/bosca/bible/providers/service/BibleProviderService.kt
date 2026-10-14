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

    /** Resolves the registered provider for an enabled configuration; operations read its saved credentials. */
    suspend fun getProvider(configurationId: UUID): BibleProvider

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

    /**
     * Looks up the server-only FUMS token for a chapter response in this exact
     * Bible variant. [usfm] identifies the chapter; [audioId] selects a recording, and null
     * selects text. Tokens survive text and media eviction until their original expiry.
     * Returns null for local content or when no unexpired token exists.
     * Never fetches content or emits Analytics events. The token must not enter client
     * responses or analytical storage.
     */
    suspend fun getFumsToken(
        bibleId: UUID,
        version: Int,
        variant: String,
        usfm: String,
        audioId: UUID? = null,
    ): String?

    /** Lists compatible exact Bible variants whose metadata has not been deleted, including disabled variants. */
    suspend fun getAudioTargets(configurationId: UUID, providerBibleId: String): List<Bible>

    /** Adds a selected text Bible as unpublished content in the existing Bibles collection. */
    suspend fun addBible(input: AddProviderBibleInput): Bible

    /** Attaches a compatible recording with reviewed playback rights; deleted or missing target metadata fails. */
    suspend fun addAudio(bible: Bible, input: AddProviderAudioInput): BibleAudio

    /** Refreshes source descriptions, languages, and chapter references without prefetching bodies. */
    suspend fun refresh(sourceId: UUID)

    /**
     * Removes a source at [expectedVersion]. A recording's retained media is evicted. Removing a text source
     * deletes the Bible variant registered from it, including that variant's chapters and attached recordings,
     * and deletes the Bible metadata when no variant remains. Usage tokens are kept until their original expiry.
     */
    suspend fun removeSource(sourceId: UUID, expectedVersion: Long)

    /**
     * Removes a configuration. Active sources prevent removal unless [purgeSources] is true;
     * callers must explicitly confirm removal of the affected sources at the API boundary.
     * Each source is removed as by [removeSource], so purged text sources delete their Bible variants.
     */
    suspend fun removeConfiguration(configurationId: UUID, purgeSources: Boolean)
}
