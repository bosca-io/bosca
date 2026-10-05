package bosca.content.metadata.service

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
     *
     * @param book the Bible book containing the chapter
     * @param usfm the USFM (Unified Standard Format Marker) identifier for the chapter
     * @return the matching Bible chapter
     */
    suspend fun getChapter(book: BibleBook, usfm: String): BibleChapter

    /**
     * Retrieves a specific chapter of a Bible using a structured Scripture reference.
     *
     * @param bible the Bible translation to look up
     * @param reference the structured reference identifying the chapter
     * @return the matching Bible chapter
     */
    suspend fun getChapter(bible: Bible, reference: Reference): BibleChapter

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
