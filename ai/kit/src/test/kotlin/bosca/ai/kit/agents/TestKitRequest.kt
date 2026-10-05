package bosca.ai.kit.agents

import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.model.ChatMessagePartInput

/**
 * Builds a [KitRequest] carrying a single user text message — the shape a real chat surface sends.
 * Tests speak in plain strings; this wraps them in the structured [ChatMessageInput] that
 * [KitRequest.message] now requires, so the test bodies stay readable.
 */
fun kitRequest(text: String): KitRequest =
    KitRequest(message = ChatMessageInput(role = "user", parts = listOf(ChatMessagePartInput(type = "text", text = text))))
