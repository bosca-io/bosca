@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * A translatable string key within a [LocalizationProject]. Holds the metadata
 * that describes the string (declared placeholders, plural flag, translator context,
 * visual context via a linked metadata item) but not the source text itself or any
 * translation; those are stored as translations in the project's source language and
 * other languages.
 *
 * @property key the stable identifier used by consumers (e.g. `"items_count"`)
 * @property context free-form description to help translators understand intent
 * @property metadataId optional reference to a metadata item whose supplementary items
 *  (screenshots, mockups) provide visual context for the translator
 * @property placeholders declared ICU placeholders, serialized as a JSON array of
 *  [LocalizationPlaceholder] objects in the database
 * @property maxLength optional character cap enforced during review
 * @property tags freeform labels for filtering and organization in the admin UI
 * @property plural when `true`, translations live in `plural_translations` keyed by
 *  CLDR [PluralCategory] rather than a single row in `translations`
 */
@Serializable
data class LocalizationString(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    val key: String,
    val context: String? = null,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID? = null,
    @Contextual
    val placeholders: JsonElement? = null,
    @ColumnName("max_length")
    val maxLength: Int? = null,
    val tags: List<String> = emptyList(),
    val plural: Boolean = false,
    @Contextual
    val created: OffsetDateTime? = null,
    @Contextual
    val modified: OffsetDateTime? = null
)
