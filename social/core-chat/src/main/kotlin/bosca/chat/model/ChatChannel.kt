package bosca.chat.model

import bosca.db.annotation.ColumnName
import bosca.security.model.PermissibleEntity
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

/**
 * A real-time chat channel that supports direct messages, group conversations,
 * and public discussions. Channels can optionally be associated with a parent
 * entity (via [groupId]) or linked to a specific domain object (via [objectType]
 * and [objectId]).
 */
@Serializable
data class ChatChannel(
    @Contextual
    override val id: UUID,
    @ColumnName("group_id")
    @Contextual
    val groupId: UUID? = null,
    val name: String,
    val type: ChatChannelType,
    val attributes: JsonElement? = null,
    @ColumnName("object_type")
    val objectType: ChatObjectType? = null,
    @ColumnName("object_id")
    @Contextual
    val objectId: UUID? = null,
) : PermissibleEntity<UUID> {

    @Transient
    override val public: Boolean = type == ChatChannelType.PUBLIC

    @Transient
    override val publicContent: Boolean = type == ChatChannelType.PUBLIC

    @Transient
    override val publicList: Boolean = type == ChatChannelType.PUBLIC

    @Transient
    override val publicSupplementary: Boolean = false

    @Transient
    override val isPublished: Boolean = true

    @Transient
    override val isAdvertised: Boolean = true

    @Transient
    override val isDeleted: Boolean = false
}
