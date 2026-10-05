package bosca.ai.chat.model

import kotlinx.serialization.Serializable


@Serializable
data class ChatSessionStatuses(val status: ChatSessionStatus)