package bosca.ai.kit.agents.chat

import ai.koog.agents.chatMemory.feature.ChatMemoryPreProcessor
import ai.koog.prompt.message.Message

/** Delimiters around the seeded broader-conversation block (see [renderConversationContext]). */
internal const val CONTEXT_OPEN = "<conversation_context>"
internal const val CONTEXT_CLOSE = "</conversation_context>"

/** Newest-last cap on seeded prior turns — bounds the prompt for long sessions. */
internal const val MAX_CONTEXT_MESSAGES = 30

/**
 * Renders the broader session [transcript] — every user turn + Kit reply across **all** routes, not just
 * this agent's own chat turns — as a delimited background block the ChatAgent reads to ground its reply,
 * RAG-style. The current user turn [currentText] is excluded (it's the actual request, sent separately),
 * and only the most recent [MAX_CONTEXT_MESSAGES] are kept. Returns "" when there is nothing prior, so the
 * first turn seeds no context.
 */
internal fun renderConversationContext(transcript: List<Message>, currentText: String): String {
    val prior = transcript
        .filter { it.role == Message.Role.User || it.role == Message.Role.Assistant }
        .filterNot { it.role == Message.Role.User && it.textContent().trim() == currentText.trim() }
        .takeLast(MAX_CONTEXT_MESSAGES)
    if (prior.isEmpty()) return ""
    val lines = prior.joinToString("\n") { message ->
        val who = if (message.role == Message.Role.Assistant) "Kit" else "User"
        "$who: ${message.textContent()}"
    }
    return "$CONTEXT_OPEN\n$lines\n$CONTEXT_CLOSE\n\n"
}

/**
 * Strips the seeded [CONTEXT_OPEN]..[CONTEXT_CLOSE] block from User messages before they enter the
 * ChatAgent's OWN persisted ChatMemory thread. The block is injected fresh every turn (for the LLM to
 * read) and removed before the prompt is stored, so the thread stays the clean turn-by-turn exchange
 * rather than accumulating a re-seeded transcript each turn (which would grow quadratically). A no-op on
 * already-clean messages, so it is safe to run on both load and store.
 */
class ConversationContextPreProcessor : ChatMemoryPreProcessor {
    override fun preprocess(messages: List<Message>): List<Message> = messages.map { message ->
        if (message !is Message.User) return@map message
        val text = message.textContent()
        val close = text.indexOf(CONTEXT_CLOSE)
        if (text.startsWith(CONTEXT_OPEN) && close >= 0) {
            Message.User(text.substring(close + CONTEXT_CLOSE.length).trimStart(), message.metaInfo, id = message.id)
        } else {
            message
        }
    }
}
