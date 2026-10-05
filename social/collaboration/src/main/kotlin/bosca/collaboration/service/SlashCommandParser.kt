package bosca.collaboration.service

import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType

/**
 * A parsed slash command extracted from a chat message.
 *
 * @param agent the agent name (e.g., "kit")
 * @param command the command verb (e.g., "summarize", "translate")
 * @param args the remaining arguments after the command
 */
data class ParsedSlashCommand(
    val agent: String,
    val command: String,
    val args: String
)

/**
 * Parses a slash command from the first TEXT content block of a message.
 * Slash commands follow the format: `/<agent> <command> [args]`
 *
 * @param content the message content blocks
 * @return the parsed command, or null if no slash command is present
 */
fun parseSlashCommand(content: List<MessageContent>): ParsedSlashCommand? {
    val textBlock = content.firstOrNull { it.type == MessageContentType.TEXT } ?: return null
    val text = textBlock.content.trim()
    if (!text.startsWith("/")) return null

    val parts = text.removePrefix("/").split(" ", limit = 3)
    if (parts.size < 2) return null

    return ParsedSlashCommand(
        agent = parts[0],
        command = parts[1],
        args = parts.getOrElse(2) { "" }
    )
}
