package bosca.content.metadata.model

import bosca.bible.IBible
import bosca.bible.bibleJson
import bosca.bible.components.IComponent
import bosca.bible.style.IStyle
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("bosca.content.metadata.model.BibleInput")

@Serializable
class BibleInput(
    val systemId: String,
    val name: String,
    val nameLocal: String,
    val abbreviation: String,
    val abbreviationLocal: String,
    val languages: List<BibleLanguageInput>,
    val description: String,
    val books: List<BookInput>,
    val defaultVariant: Boolean,
    @Contextual
    val styles: JsonElement,
    val variant: String
) {

    fun toBible(id: UUID, version: Int): Bible {
        return Bible(
            metadataId = id,
            version = version,
            systemId = systemId,
            variant = variant,
            defaultVariant = defaultVariant,
            name = name,
            nameLocal = nameLocal,
            description = description,
            abbreviation = abbreviation,
            abbreviationLocal = abbreviationLocal,
            styles = styles,
        )
    }
}

/** Projects a Bible for persistence using its publication identifier as the variant. */
fun IBible.toInput(defaultVariant: Boolean) = toInput(defaultVariant, metadata.publication.id)

/** Projects a Bible for persistence with an explicitly selected Bosca variant. */
fun IBible.toInput(defaultVariant: Boolean, variant: String) = BibleInput(
    systemId = metadata.identification.system.id,
    name = metadata.identification.name,
    nameLocal = metadata.identification.nameLocal,
    abbreviation = metadata.identification.abbreviation,
    abbreviationLocal = metadata.identification.abbreviationLocal,
    languages = listOf(
        metadata.language.let {
            BibleLanguageInput(
                iso = it.iso,
                name = it.name,
                nameLocal = it.nameLocal ?: it.name,
                script = it.script,
                scriptCode = it.scriptCode,
                scriptDirection = it.scriptDirection
            )
        }
    ),
    description = metadata.identification.description,
    books = books.map {
        BookInput(
            abbreviation = it.name.abbreviation,
            chapters = it.chapters.map {
                val content = it[it.reference]
                val components = try {
                    bibleJson.encodeToJsonElement(IComponent.serializer().nullable, content)
                } catch (e: Exception) {
                    log.error("Error parsing content: {}", content, e)
                    throw e
                }
                ChapterInput(
                    component = components,
                    reference = ReferenceInput(it.reference.usfm)
                )
            },
            nameLong = it.name.long,
            nameShort = it.name.short,
            reference = ReferenceInput(
                usfm = it.reference.usfm
            )
        )
    },
    defaultVariant = defaultVariant,
    styles = bibleJson.encodeToJsonElement(ListSerializer(PolymorphicSerializer(IStyle::class)), styles),
    variant = variant
)
