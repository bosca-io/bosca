@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.db.annotation.ColumnName
import bosca.security.model.PermissibleEntity
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * A logical grouping of translatable strings and documents for a specific application
 * or context (for example an iOS app, a marketing site, or a customer portal).
 *
 * Projects carry their own security boundary: three project-scoped groups are created
 * on insert (`translator-viewer:{projectId}`, `translator-contributor:{projectId}`,
 * `translator-manager:{projectId}`) and used by [LocalizationProjectPermissionEvaluator]
 * to authorize operations against individual projects.
 */
@Serializable
data class LocalizationProject(
    @Contextual
    override val id: UUID = UUID.NIL,
    val name: String,
    val description: String? = null,
    @ColumnName("source_language")
    val sourceLanguage: String = "en",
    @Contextual
    val attributes: JsonElement? = null,
    @Contextual
    val created: OffsetDateTime? = null,
    @Contextual
    val modified: OffsetDateTime? = null
) : PermissibleEntity<UUID> {

    @Transient
    override val public: Boolean = false

    @Transient
    override val publicContent: Boolean = false

    @Transient
    override val publicList: Boolean = false

    @Transient
    override val publicSupplementary: Boolean = false

    /** Localization projects are always "published" for permission evaluation purposes. */
    @Transient
    override val isPublished: Boolean = true

    @Transient
    override val isAdvertised: Boolean = false

    @Transient
    override val isDeleted: Boolean = false
}
