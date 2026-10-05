package bosca.collaboration.service

import bosca.chat.service.ChatService
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class ChatAgentServiceImpl(
    private val chatService: ChatService,
    private val profileService: ProfileService,
) : ChatAgentService {

    override suspend fun processAndRespond(
        channelId: UUID,
        senderId: UUID,
        sequence: Long,
        slashCommand: String?,
    ) {
        // No-op: the chat-agent execution path was built on Google ADK, which has been
        // removed from the platform. Reimplement on the koog-based kit agent stack
        // (see ai/kit) before wiring chat responses back on.
    }
}
