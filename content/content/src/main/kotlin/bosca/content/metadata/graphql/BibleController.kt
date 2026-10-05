package bosca.content.metadata.graphql

import bosca.bible.Reference
import bosca.bible.components.filter
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleBookChapter
import bosca.content.metadata.model.FindBibleResult
import bosca.content.metadata.model.toJson
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.json.JsonArray


@TypeController
class BibleController(
    private val bibleService: BibleService,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<Bible> {

    @Field
    fun systemId(bible: Bible) = bible.systemId

    @Field
    fun variant(bible: Bible) = bible.variant

    @Field
    fun defaultVariant(bible: Bible) = bible.defaultVariant

    @Field
    fun enabled(bible: Bible) = bible.enabled

    @Field
    suspend fun variants(
        authentication: AuthenticationContext?,
        bible: Bible,
        includeDisabled: Boolean?,
    ): List<Bible> {
        val variants = bibleService.getVariants(bible.metadataId, bible.version)
        if (includeDisabled != true) return variants.filter { it.enabled }
        val metadata = metadataService.getById(bible.metadataId, bible.version)
            ?: throw NoSuchElementException("Metadata not found: ${bible.metadataId}")
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        return variants
    }

    @Field
    fun name(bible: Bible) = bible.name

    @Field
    fun nameLocal(bible: Bible) = bible.nameLocal

    @Field
    fun abbreviation(bible: Bible) = bible.abbreviation

    @Field
    fun abbreviationLocal(bible: Bible) = bible.abbreviationLocal

    @Field
    fun description(bible: Bible) = bible.description

    @Field
    fun styles(bible: Bible) = bible.styles

    @Field
    suspend fun languages(bible: Bible) = bibleService.getLanguages(bible)

    @Field
    suspend fun books(bible: Bible) = bibleService.getBooks(bible)

    @Field
    suspend fun chapter(bible: Bible, usfm: String): BibleBookChapter? {
        val reference = Reference(usfm)
        val book = bibleService.getBooks(bible).firstOrNull { it.usfm == reference.bookUsfm } ?: return null
        return BibleBookChapter(book, bibleService.getChapter(book, usfm))
    }

    @Field
    suspend fun book(bible: Bible, usfm: String): BibleBook? {
        return bibleService.getBooks(bible).firstOrNull { it.usfm == usfm }
    }

    @Field
    suspend fun find(bible: Bible, human: String): List<FindBibleResult> {
        val references = bibleService.getReferences(bible, human)
        val books = bibleService.getBooks(bible)
        return references.mapNotNull { reference ->
            val book = books.find { it.usfm == reference.bookUsfm } ?: return@mapNotNull null
            val chapter = bibleService.getChapter(book, reference.chapterUsfm)
            val components = chapter.getChapterComponents() ?: return@mapNotNull null
            val filtered = references.mapNotNull {
                components.filter(it)?.toJson()
            }
            FindBibleResult(
                book = book,
                chapter = chapter,
                component = JsonArray(filtered),
                human = bibleService.getHuman(bible, reference),
                humanShort = bibleService.getHumanLong(bible, reference),
                reference = reference
            )
        }
    }
}
