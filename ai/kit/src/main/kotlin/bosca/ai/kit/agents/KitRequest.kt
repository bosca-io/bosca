package bosca.ai.kit.agents

import bosca.ai.chat.model.ChatMessageInput
import bosca.content.collection.model.Collection
import bosca.content.metadata.model.BibleReference
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Metadata
import bosca.profile.model.Profile
import bosca.security.model.Principal
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class KitRequestIdentity(
    val principal: Principal,
    val profile: Profile?,
)

/**
 * What Kit is asked to do. Today just the user's [message], but modeled as a type rather than a
 * bare `String` so a request can grow to carry context — a metadata reference being discussed,
 * attachments, the surface it came from — without changing every signature in the planner.
 * `@Serializable` so the planner can snapshot/restore a session (it is part of [KitState]).
 */
@Serializable
data class KitRequest(
    val identity: KitRequestIdentity? = null,
    val metadata: Metadata? = null,
    val document: DocumentInput? = null,
    val collection: Collection? = null,
    val profile: Profile? = null,
    val references: List<BibleReference> = emptyList(),
    val message: ChatMessageInput,
)
