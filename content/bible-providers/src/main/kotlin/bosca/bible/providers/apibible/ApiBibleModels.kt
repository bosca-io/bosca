package bosca.bible.providers.apibible

import bosca.bible.BibleAttribution
import bosca.bible.IBibleLanguage
import bosca.bible.providers.model.BibleSourceKind
import bosca.bible.providers.model.IBibleCatalog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.util.Locale

@Serializable
internal data class ApiBibleResponse<T>(val data: T, val meta: ApiBibleMeta? = null)

@Serializable
internal data class ApiBibleMeta(val fumsToken: String? = null) {
    override fun toString() = "ApiBibleMeta(fumsToken=[redacted])"
}

@Serializable
internal data class ApiBibleLanguage(
    @SerialName("id") override val iso: String,
    override val name: String,
    override val nameLocal: String? = null,
    override val script: String,
    override val scriptDirection: String,
) : IBibleLanguage {
    override val scriptCode: String
        get() = Locale.getAvailableLocales().firstOrNull {
            it.script.isNotBlank() && (it.script.equals(script, ignoreCase = true) ||
                it.getDisplayScript(Locale.ENGLISH).equals(script, ignoreCase = true))
        }?.script ?: throw IOException("API.Bible returned an unrecognized script: $script")
}

@Serializable
internal data class ApiBibleCatalog(
    override val id: String,
    @SerialName("dblId") override val systemId: String,
    override val name: String,
    override val nameLocal: String,
    override val abbreviation: String,
    override val abbreviationLocal: String,
    val language: ApiBibleLanguage,
    @SerialName("description") private val providerDescription: String? = null,
    val descriptionLocal: String? = null,
    val copyright: String? = null,
    @SerialName("relatedDbl") override val relatedSystemId: String? = null,
    override val kind: BibleSourceKind = BibleSourceKind.TEXT,
) : IBibleCatalog {
    override val description: String get() = providerDescription.orEmpty()
    override val languages: List<IBibleLanguage> get() = listOf(language)
    override val attribution: BibleAttribution get() = BibleAttribution(copyright.orEmpty())
}

@Serializable
internal data class ApiBibleBook(
    @SerialName("id") val usfm: String,
    val name: String,
    val nameLong: String,
    val abbreviation: String? = null,
    val chapters: List<ApiBibleChapterReference>,
)

@Serializable
internal data class ApiBibleChapterReference(
    @SerialName("id") val usfm: String,
    @SerialName("bookId") val bookUsfm: String,
)

@Serializable
internal data class ApiBibleChapter(
    @SerialName("id") val usfm: String,
    val bibleId: String,
    @SerialName("bookId") val bookUsfm: String,
    val content: List<ApiBibleNode>,
    val copyright: String? = null,
    val verseCount: Int,
)

@Serializable
internal data class ApiBibleAudioChapter(
    @SerialName("id") val usfm: String,
    val bibleId: String,
    @SerialName("bookId") val bookUsfm: String,
    val resourceUrl: String,
    val copyright: String? = null,
    val timecodes: List<ApiBibleTimecode> = emptyList(),
) {
    override fun toString() = "ApiBibleAudioChapter(usfm=$usfm, resourceUrl=[redacted])"
}

@Serializable
internal data class ApiBibleTimecode(
    @SerialName("verseId") val usfm: String,
    val start: String,
    val end: String,
)

@Serializable
internal data class ApiBibleNode(
    val type: String,
    val name: String? = null,
    val text: String? = null,
    val attrs: ApiBibleAttributes = ApiBibleAttributes(),
    val items: List<ApiBibleNode> = emptyList(),
)

@Serializable
internal data class ApiBibleAttributes(
    val style: String? = null,
    val number: String? = null,
    val sid: String? = null,
    @SerialName("verseId") val usfm: String? = null,
    @SerialName("verseOrgIds") val references: List<String> = emptyList(),
)
