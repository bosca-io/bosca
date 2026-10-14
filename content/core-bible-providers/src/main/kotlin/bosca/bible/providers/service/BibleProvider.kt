package bosca.bible.providers.service

import bosca.bible.IBible
import bosca.bible.IBibleAudioContent
import bosca.bible.IChapter
import bosca.bible.providers.model.IBibleCatalog
import bosca.bible.providers.model.IBibleProviderResponse
import bosca.bible.providers.model.BibleSourceKind
import bosca.serialization.UUID
import java.io.OutputStream

/**
 * Access to a Bible provider using the saved configuration selected for each operation.
 * Provider identifiers are opaque and independent
 * of Bosca metadata identifiers. Implementations preserve Scripture content and propagate
 * cancellation and provider failures.
 */
interface BibleProvider {

    /** Stable provider key used by [bosca.bible.providers.model.BibleProviderConfiguration]. */
    val key: String

    /** Verifies saved credentials, including disabled configurations, without storing content. */
    suspend fun test(configurationId: UUID)

    /** Lists accessible text Bibles or recordings using an enabled configuration without adding content. */
    suspend fun getCatalog(
        configurationId: UUID,
        kind: BibleSourceKind,
        query: String?,
        offset: Int,
        limit: Int,
    ): List<IBibleCatalog>

    /**
     * Loads a Bible through the shared IBible, IBook, and IChapter contracts. Chapter
     * references are available before their text is fetched. Callers materialize serializable
     * models at the persistence boundary and assign Bosca metadata identity, variant
     * availability, and default selection through the content integration.
     * Credentials come from the enabled saved [configurationId] for this operation.
     */
    suspend fun getBible(configurationId: UUID, providerBibleId: String, kind: BibleSourceKind): IBibleProviderResponse<IBible>

    /** Fetches one text chapter and its exact response metadata using the enabled saved configuration. */
    suspend fun getChapter(configurationId: UUID, providerBibleId: String, usfm: String): IBibleProviderResponse<IChapter>

    /**
     * Downloads one chapter recording before its temporary provider URL expires. The caller
     * owns [destination]; implementations must not close it. The result describes the bytes
     * written and carries the usage token from the corresponding provider response. Temporary
     * URLs remain inside the provider and provider credentials are not forwarded to media hosts.
     * Credentials come from the enabled saved [configurationId] for this operation.
     */
    suspend fun downloadAudioChapter(
        configurationId: UUID,
        providerBibleId: String,
        usfm: String,
        destination: OutputStream,
    ): IBibleProviderResponse<IBibleAudioContent>
}
