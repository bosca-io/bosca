package bosca.forms.model

import bosca.db.annotation.ColumnName
import bosca.security.model.PermissibleEntity
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A reusable form definition backed by JSON Schema for data validation
 * and a companion UI Schema for layout and control configuration.
 * Identified by a unique string key for component-level lookup
 * (e.g. "org-profile", "newsletter-signup").
 *
 * Implements [PermissibleEntity] so access is governed by the same
 * group-based permission model used by metadata, collections, and profiles.
 */
@Serializable
data class FormSchema(
    @Contextual
    override val id: UUID,
    val type: FormSchemaType = FormSchemaType.INTERNAL,
    val key: String,
    val name: String,
    val description: String,
    @Contextual
    val schema: JsonElement,
    @Contextual
    @ColumnName("ui_schema")
    val uiSchema: JsonElement,
    val version: Int,
    override val public: Boolean = false,
    @ColumnName("public_content")
    override val publicContent: Boolean = false,
    @ColumnName("public_list")
    override val publicList: Boolean = false,
    @ColumnName("public_supplementary")
    override val publicSupplementary: Boolean = false,
    @Contextual
    val configuration: JsonElement? = null,
    @Contextual
    @ColumnName("profile_mapping")
    val profileMapping: JsonElement? = null,
    val published: Boolean = false,
    val deleted: Boolean = false,
    val created: OffsetDateTime,
    val modified: OffsetDateTime,
) : PermissibleEntity<UUID> {

    override val isPublished: Boolean
        get() = published

    override val isAdvertised: Boolean
        get() = false

    override val isDeleted: Boolean
        get() = deleted
}
