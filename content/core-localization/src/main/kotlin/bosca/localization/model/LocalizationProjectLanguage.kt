@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * A declared target language for a [LocalizationProject]. Projects list every language
 * they intend to translate into so the admin UI can show progress per language and
 * constrain the language selector to known targets.
 */
@Serializable
data class LocalizationProjectLanguage(
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("language_tag")
    val languageTag: String,
    @Contextual
    val created: OffsetDateTime? = null
)
