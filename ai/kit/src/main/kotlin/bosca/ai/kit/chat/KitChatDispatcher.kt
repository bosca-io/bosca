package bosca.ai.kit.chat

import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.service.ChatDispatcher
import bosca.ai.kit.harness.KitHarnessService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

/** Acceptance is durable; native inference and runtime lifecycle belong to Bosca Runner. */
class KitChatDispatcher(private val harness: KitHarnessService) : ChatDispatcher {
    override suspend fun dispatch(authenticationContext: AuthenticationContext, sessionId: UUID, message: ChatMessageInput) {
        harness.send(authenticationContext, sessionId, message)
    }
}
