package bosca.bible.providers.model

import bosca.bible.BibleAttribution
import bosca.bible.BibleLanguage
import bosca.bible.IBibleAttribution
import bosca.bible.IBibleLanguage
import kotlinx.serialization.Serializable

/** Provider catalog information projected without materializing Bible content. */
interface IBibleCatalog {

    val id: String
    val systemId: String
    val kind: BibleSourceKind
    val name: String
    val nameLocal: String
    val abbreviation: String
    val abbreviationLocal: String
    val description: String
    val languages: List<IBibleLanguage>
    val attribution: IBibleAttribution
    val relatedSystemId: String?

    /** Captures the catalog description and converts languages and attribution for serialization. */
    fun asSerializable(): BibleCatalog = BibleCatalog(
        id,
        systemId,
        kind,
        name,
        nameLocal,
        abbreviation,
        abbreviationLocal,
        description,
        languages.map { it.asSerializable() },
        attribution.asSerializable(),
        relatedSystemId,
    )
}

/**
 * A text Bible or recording available from a provider, without chapter bodies or local
 * metadata identity. [systemId] is the publication identity shared with DBL content;
 * [relatedSystemId] identifies the text Bible associated with a recording when supplied.
 */
@Serializable
data class BibleCatalog(
    override val id: String,
    override val systemId: String,
    override val kind: BibleSourceKind,
    override val name: String,
    override val nameLocal: String,
    override val abbreviation: String,
    override val abbreviationLocal: String,
    override val description: String,
    override val languages: List<BibleLanguage>,
    override val attribution: BibleAttribution,
    override val relatedSystemId: String? = null,
) : IBibleCatalog
