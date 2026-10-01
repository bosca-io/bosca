package bosca.search.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexConfigurationTest {

    @Test
    fun `IndexConfiguration stores name`() {
        val config = IndexConfiguration(name = "test-index")
        assertEquals("test-index", config.name)
    }

    @Test
    fun `IndexConfiguration default primaryKey is id`() {
        val config = IndexConfiguration(name = "test")
        assertEquals("id", config.primaryKey)
    }

    @Test
    fun `IndexConfiguration contentIndex defaults to true`() {
        val config = IndexConfiguration(name = "test")
        assertTrue(config.contentIndex)
    }

    @Test
    fun `IndexConfiguration contentIndex can be set to false`() {
        val config = IndexConfiguration(name = "test", contentIndex = false)
        assertFalse(config.contentIndex)
    }

    @Test
    fun `IndexConfiguration default lists are empty`() {
        val config = IndexConfiguration(name = "test")
        assertTrue(config.filterable.isEmpty())
        assertTrue(config.sortable.isEmpty())
        assertTrue(config.searchable.isEmpty())
        assertTrue(config.embedders.isEmpty())
        assertTrue(config.chatSettings.isEmpty())
    }

    @Test
    fun `IndexConfiguration chat defaults to null`() {
        val config = IndexConfiguration(name = "test")
        assertNull(config.chat)
    }

    @Test
    fun `IndexConfiguration with custom primaryKey`() {
        val config = IndexConfiguration(name = "test", primaryKey = "custom_id")
        assertEquals("custom_id", config.primaryKey)
    }

    @Test
    fun `IndexConfiguration with filterable and sortable fields`() {
        val config = IndexConfiguration(
            name = "test",
            filterable = listOf("category", "status"),
            sortable = listOf("created", "title"),
            searchable = listOf("title", "content")
        )
        assertEquals(listOf("category", "status"), config.filterable)
        assertEquals(listOf("created", "title"), config.sortable)
        assertEquals(listOf("title", "content"), config.searchable)
    }

    @Test
    fun `IndexEmbedder stores all fields`() {
        val embedder = IndexEmbedder(
            name = "openai",
            source = "openAi",
            model = "text-embedding-3-small",
            dimensions = 1536,
            apiKey = "test-key",
            documentTemplate = "{{ doc.title }}",
            documentTemplateMaxBytes = 500
        )
        assertEquals("openai", embedder.name)
        assertEquals("openAi", embedder.source)
        assertEquals("text-embedding-3-small", embedder.model)
        assertEquals(1536, embedder.dimensions)
        assertEquals("test-key", embedder.apiKey)
        assertEquals("{{ doc.title }}", embedder.documentTemplate)
        assertEquals(500, embedder.documentTemplateMaxBytes)
    }

    @Test
    fun `IndexEmbedder defaults for optional fields`() {
        val embedder = IndexEmbedder(name = "test", source = "openAi", model = "test-model")
        assertNull(embedder.dimensions)
        assertNull(embedder.apiKey)
        assertNull(embedder.documentTemplate)
        assertEquals(400, embedder.documentTemplateMaxBytes)
    }

    @Test
    fun `IndexChatConfiguration stores all fields`() {
        val config = IndexChatConfiguration(
            description = "Chat about docs",
            documentTemplate = "{{ doc.content }}",
            documentTemplateMaxBytes = 1000
        )
        assertEquals("Chat about docs", config.description)
        assertEquals("{{ doc.content }}", config.documentTemplate)
        assertEquals(1000, config.documentTemplateMaxBytes)
        assertNull(config.searchParameters)
    }

    @Test
    fun `IndexChatSearchParameters stores hybrid and limit`() {
        val hybrid = IndexChatHybrid(semanticRatio = 0.8, embedder = "openai")
        val params = IndexChatSearchParameters(hybrid = hybrid, limit = 5)
        assertEquals(hybrid, params.hybrid)
        assertEquals(5, params.limit)
    }

    @Test
    fun `IndexChatSearchParameters limit defaults to null`() {
        val hybrid = IndexChatHybrid(embedder = "openai")
        val params = IndexChatSearchParameters(hybrid = hybrid)
        assertNull(params.limit)
    }

    @Test
    fun `IndexChatHybrid semanticRatio defaults to null`() {
        val hybrid = IndexChatHybrid(embedder = "test")
        assertNull(hybrid.semanticRatio)
        assertEquals("test", hybrid.embedder)
    }

    @Test
    fun `IndexChatSettingsConfiguration stores fields`() {
        val settings = IndexChatSettingsConfiguration(
            name = "gpt-4",
            source = "openAi",
            apiKey = "key"
        )
        assertEquals("gpt-4", settings.name)
        assertEquals("openAi", settings.source)
        assertEquals("key", settings.apiKey)
        assertNull(settings.prompts)
    }

    @Test
    fun `IndexChatSearchPrompt stores fields`() {
        val prompt = IndexChatSearchPrompt(
            system = "You are a helpful assistant",
            searchDescription = "Search for relevant docs",
            searchQParam = "q",
            searchIndexUidParam = "index"
        )
        assertEquals("You are a helpful assistant", prompt.system)
        assertEquals("Search for relevant docs", prompt.searchDescription)
        assertEquals("q", prompt.searchQParam)
        assertEquals("index", prompt.searchIndexUidParam)
    }

    @Test
    fun `IndexChatSearchPrompt optional fields default to null`() {
        val prompt = IndexChatSearchPrompt(system = "system prompt")
        assertNull(prompt.searchDescription)
        assertNull(prompt.searchQParam)
        assertNull(prompt.searchIndexUidParam)
    }
}
