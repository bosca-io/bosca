package bosca.collaboration.service

import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlashCommandParserTest {

    @Test
    fun `parses simple slash command`() {
        val content = listOf(MessageContent(MessageContentType.TEXT, "/kit summarize"))
        val result = parseSlashCommand(content)
        assertEquals("kit", result?.agent)
        assertEquals("summarize", result?.command)
        assertEquals("", result?.args)
    }

    @Test
    fun `parses slash command with arguments`() {
        val content = listOf(MessageContent(MessageContentType.TEXT, "/kit translate fr"))
        val result = parseSlashCommand(content)
        assertEquals("kit", result?.agent)
        assertEquals("translate", result?.command)
        assertEquals("fr", result?.args)
    }

    @Test
    fun `parses slash command with multi-word arguments`() {
        val content = listOf(MessageContent(MessageContentType.TEXT, "/kit search find all metadata about images"))
        val result = parseSlashCommand(content)
        assertEquals("kit", result?.agent)
        assertEquals("search", result?.command)
        assertEquals("find all metadata about images", result?.args)
    }

    @Test
    fun `returns null for non-slash message`() {
        val content = listOf(MessageContent(MessageContentType.TEXT, "hello world"))
        assertNull(parseSlashCommand(content))
    }

    @Test
    fun `returns null for slash with only agent name`() {
        val content = listOf(MessageContent(MessageContentType.TEXT, "/kit"))
        assertNull(parseSlashCommand(content))
    }

    @Test
    fun `returns null for empty content`() {
        assertNull(parseSlashCommand(emptyList()))
    }

    @Test
    fun `ignores non-text content blocks`() {
        val content = listOf(MessageContent(MessageContentType.IMAGE, "/kit summarize"))
        assertNull(parseSlashCommand(content))
    }

    @Test
    fun `trims whitespace before parsing`() {
        val content = listOf(MessageContent(MessageContentType.TEXT, "  /kit summarize  "))
        val result = parseSlashCommand(content)
        assertEquals("kit", result?.agent)
        assertEquals("summarize", result?.command)
    }
}
