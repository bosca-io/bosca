package bosca.chat.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Identifies the type of domain object that a chat channel is scoped to,
 * enabling contextual discussions about specific content within the system.
 */
@DbMapper(ChatObjectTypeMapper::class)
@Serializable
enum class ChatObjectType {
    /** A content metadata item */
    METADATA,
    /** A content collection */
    COLLECTION,
    /** A localization string or document key */
    LOCALIZATION_KEY,
    /** A calendar event (future) */
    CALENDAR_EVENT,
    /** A feature flag (future) */
    FEATURE_FLAG,
    /** An experiment configuration (future) */
    EXPERIMENT
}

object ChatObjectTypeMapper : EnumMapper<ChatObjectType>({ ChatObjectType.valueOf(it.uppercase()) })
