package bosca.content.metadata.service

import bosca.bible.BibleContentUse
import bosca.bible.IBibleAudio
import bosca.bible.IBibleAudioChapter
import bosca.bible.IBibleChapterContent
import bosca.bible.Reference
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.BibleInput
import bosca.content.metadata.model.BibleLanguage
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing Bible content, including translations, books, chapters, and
 * Scripture references. Provides operations for CRUD on Bible data and reference
 * resolution between machine-readable references and human-readable citation strings.
 */
interface BibleService : Service {

    suspend fun getBibles(): List<Bible>

    /**
     * Retrieves every Bible variant stored for a metadata version, including disabled variants.
     * Callers are responsible for applying API-boundary authorization before exposing disabled
     * variants.
     *
     * @param id the metadata identifier of the Bible
     * @param version the metadata version number
     * @return the variants in stable display order
     */
    suspend fun getVariants(id: UUID, version: Int): List<Bible>

    /**
     * Retrieves a Bible translation by its metadata identifier, version, and optional variant.
     *
     * @param id the metadata identifier of the Bible
     * @param version the metadata version number
     * @param variant an optional variant identifier (e.g., a specific translation variant)
     * @return the Bible, or null if not found
     */
    suspend fun getBible(id: UUID, version: Int, variant: String?): Bible?

    /**
     * Enables or disables a Bible variant. The default variant cannot be disabled.
     *
     * @param id the metadata identifier of the Bible
     * @param version the metadata version number
     * @param variant the variant identifier
     * @param enabled whether the variant should be available
     * @return the updated variant
     */
    suspend fun setVariantEnabled(id: UUID, version: Int, variant: String, enabled: Boolean): Bible

    /**
     * Selects the enabled default variant for a Bible metadata version.
     *
     * @param id the metadata identifier of the Bible
     * @param version the metadata version number
     * @param variant the enabled variant to make the default
     * @return the updated default variant
     */
    suspend fun setDefaultVariant(id: UUID, version: Int, variant: String): Bible

    /**
     * Retrieves all available languages for a given Bible translation.
     *
     * @param bible the Bible whose languages should be retrieved
     * @return the list of languages available for this Bible
     */
    suspend fun getLanguages(bible: Bible): List<BibleLanguage>

    /**
     * Retrieves all books within a given Bible translation.
     *
     * @param bible the Bible whose books should be retrieved
     * @return the list of books in this Bible
     */
    suspend fun getBooks(bible: Bible): List<BibleBook>

    /**
     * Retrieves all chapters within a given Bible book.
     *
     * @param book the Bible book whose chapters should be retrieved
     * @return the list of chapters in this book
     */
    suspend fun getChapters(book: BibleBook): List<BibleChapter>

    /**
     * Retrieves a specific chapter of a Bible book by its USFM identifier.
     * Provider chapters include their reference and source identity; use [getChapterContent]
     * to read their content with the requested use checks.
     *
     * @param book the Bible book containing the chapter
     * @param usfm the USFM (Unified Standard Format Marker) identifier for the chapter
     * @return the matching Bible chapter
     */
    suspend fun getChapter(book: BibleBook, usfm: String): BibleChapter

    /**
     * Retrieves a specific chapter of a Bible using a structured Scripture reference.
     * Provider chapter bodies and cache metadata are omitted; use [getChapterContent] to read them.
     *
     * @param bible the Bible translation to look up
     * @param reference the structured reference identifying the chapter
     * @return the matching Bible chapter
     */
    suspend fun getChapter(bible: Bible, reference: Reference): BibleChapter

    /**
     * Reads chapter content and attribution for this exact Bible variant. External content
     * must permit the requested [use]; local DBL content has no provider restriction.
     * Provider attribution comes from the exact content response, falling back to its source.
     * Reads use the service caches and reject expired provider responses without extending expiry.
     * A skipped cache write still returns the fetched response.
     * Complete chapters are returned regardless of size; background maintenance enforces retention limits.
     */
    suspend fun getChapterContent(book: BibleBook, usfm: String, use: BibleContentUse): IBibleChapterContent

    /**
     * Reads the chapter containing [reference] with the same use checks and attribution as
     * the book-based overload. The returned chapter supports selecting individual verses.
     */
    suspend fun getChapterContent(bible: Bible, reference: Reference, use: BibleContentUse): IBibleChapterContent

    /** Attaches an external text source to the existing chapters of an exact Bible variant. */
    suspend fun setChapterSource(id: UUID, version: Int, variant: String, sourceId: UUID)

    /** Clears retained text or recordings and private response metadata, preserving chapter references; media deletion is queued. */
    suspend fun clearChapterContent(sourceId: UUID): Int

    /** Clears expired provider text and recordings without changing locally imported chapters; media deletion is queued. */
    suspend fun removeExpiredChapterContent(): Int

    /**
     * Trims an upstream edition to 499 retained verses across configurations, keeping newest responses first.
     * Called by background maintenance; returns the number of complete chapter responses cleared.
     */
    suspend fun limitChapterContent(provider: String, providerBibleId: String): Int

    /** Reads a private token from an unexpired provider response without fetching chapter text. */
    suspend fun getFumsToken(id: UUID, version: Int, variant: String, usfm: String): String?

    /** Lists recordings attached to this exact Bible variant without downloading chapter media. */
    suspend fun getAudio(bible: Bible): List<IBibleAudio>

    /** Reads a chapter recording with playback permission, attribution, timing and an absolute Bosca playback URL. */
    suspend fun getAudioChapter(bible: Bible, audioId: UUID, usfm: String): IBibleAudioChapter

    /**
     * Resolves a recording with playback permission. Its metadata and streams refer to the same file.
     * When [recordingId] is supplied, returns only that current recording; unavailable recordings are not replaced.
     * Open and close its streams within the current authorized request.
     */
    suspend fun getAudioPlayback(bible: Bible, audioId: UUID, usfm: String, recordingId: UUID? = null): BibleAudioPlayback

    /** Reads an unexpired recording response's private usage token without downloading media. */
    suspend fun getAudioFumsToken(id: UUID, version: Int, variant: String, usfm: String, audioId: UUID): String?

    /** Trims one upstream recording to 50 retained files across configurations, keeping newest responses first. */
    suspend fun limitAudioChapterContent(provider: String, providerBibleId: String): Int

    /** Clears recordings for every version of metadata before its owning content is deleted; media deletion is queued. */
    suspend fun clearAudioChapterContent(id: UUID): Int

    /**
     * Converts a structured Scripture reference into a short human-readable citation string
     * (e.g., "Gen 1:1").
     *
     * @param bible the Bible translation providing the naming context
     * @param reference the structured reference to convert
     * @return the short human-readable citation string
     */
    suspend fun getHuman(bible: Bible, reference: Reference): String

    /**
     * Converts a structured Scripture reference into a long-form human-readable citation string
     * (e.g., "Genesis 1:1").
     *
     * @param bible the Bible translation providing the naming context
     * @param reference the structured reference to convert
     * @return the long-form human-readable citation string
     */
    suspend fun getHumanLong(bible: Bible, reference: Reference): String

    /**
     * Parses a human-readable citation string and resolves it to structured Scripture references.
     * A single human-readable string may resolve to multiple references (e.g., "Gen 1:1-3").
     *
     * @param bible the Bible translation providing the naming context
     * @param human the human-readable citation string to parse
     * @return the list of structured references matching the citation
     */
    suspend fun getReferences(bible: Bible, human: String): List<Reference>

    /**
     * Deletes a Bible translation associated with a specific metadata version.
     *
     * @param id the metadata identifier of the Bible
     * @param version the metadata version number
     */
    suspend fun deleteBible(id: UUID, version: Int)

    /**
     * Creates or replaces the Bible translation data for a specific metadata version.
     *
     * @param id the metadata identifier of the Bible
     * @param version the metadata version number
     * @param bible the Bible data to persist
     */
    suspend fun setBible(id: UUID, version: Int, bible: BibleInput)
}
