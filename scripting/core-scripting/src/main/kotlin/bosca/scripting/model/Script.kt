@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.model

import bosca.db.annotation.ColumnName
import bosca.security.model.PermissibleEntity
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

@Serializable
data class Script(
    @Contextual
    override val id: UUID = UUID.NIL,
    val key: String,
    val name: String,
    val description: String = "",
    val type: ScriptType = ScriptType.GENERAL,
    val source: String,
    val version: Int = 1,
    val enabled: Boolean = true,
    override val public: Boolean = false,
    @Contextual
    @ColumnName("input_schema")
    val inputSchema: JsonElement? = null,
    @Contextual
    @ColumnName("output_schema")
    val outputSchema: JsonElement? = null,
    @Contextual
    val configuration: JsonElement? = null,
    @Contextual
    val created: OffsetDateTime? = null,
    @Contextual
    val modified: OffsetDateTime? = null,
    @Contextual
    @ColumnName("deleted_at")
    val deletedAt: OffsetDateTime? = null
) : PermissibleEntity<UUID> {

    @Transient
    override val publicContent: Boolean = false

    @Transient
    override val publicList: Boolean = false

    @Transient
    override val publicSupplementary: Boolean = false

    /** An enabled script is considered "published" for permission evaluation purposes. */
    override val isPublished: Boolean
        get() = enabled

    @Transient
    override val isAdvertised: Boolean = false

    override val isDeleted: Boolean
        get() = deletedAt != null
}
