package bosca.bible.providers.service

import bosca.bible.IBible
import bosca.bible.IBibleAudioContent
import bosca.bible.IChapter
import bosca.bible.providers.model.IBibleCatalog
import bosca.bible.providers.model.IBibleProviderResponse
import bosca.bible.providers.model.BibleSourceKind
import java.io.OutputStream

/**
 * Access to one configured Bible provider. Provider identifiers are opaque and independent
 * of Bosca metadata identifiers. Implementations preserve Scripture content and propagate
 * cancellation and provider failures.
 */
interface BibleProvider {

    /** Stable provider key used by [bosca.bible.providers.model.BibleProviderConfiguration]. */
    val key: String

    /** Verifies access using the configured credentials without storing content. */
    suspend fun test()

    /** Lists accessible text Bibles or audio recordings without adding them to Bosca. */
    suspend fun getCatalog(
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
     */
    suspend fun getBible(providerBibleId: String, kind: BibleSourceKind): IBibleProviderResponse<IBible>

    /** Fetches one text chapter and the attribution and usage token from that exact response. */
    suspend fun getChapter(providerBibleId: String, usfm: String): IBibleProviderResponse<IChapter>

    /**
     * Downloads one chapter recording before its temporary provider URL expires. The caller
     * owns [destination]; implementations must not close it. The result describes the bytes
     * written and carries the usage token from the corresponding provider response. Temporary
     * URLs remain inside the provider and provider credentials are not forwarded to media hosts.
     */
    suspend fun downloadAudioChapter(
        providerBibleId: String,
        usfm: String,
        destination: OutputStream,
    ): IBibleProviderResponse<IBibleAudioContent>
}
