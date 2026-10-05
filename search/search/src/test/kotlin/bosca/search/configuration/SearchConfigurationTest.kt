package bosca.search.configuration

import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MeilisearchConfigurationTest {

    @Test
    fun fieldPreservation() {
        val config = MeilisearchConfiguration(
            url = "http://localhost:7700",
            apiKey = "masterKey",
            experimental = ExperimentalConfiguration(chatCompletions = true)
        )
        assertEquals("http://localhost:7700", config.url)
        assertEquals("masterKey", config.apiKey)
        assertEquals(true, config.experimental?.chatCompletions)
    }

    @Test
    fun defaultExperimentalIsNull() {
        val config = MeilisearchConfiguration(url = "http://localhost:7700", apiKey = "key")
        assertNull(config.experimental)
    }

    @Test
    fun equality() {
        val a = MeilisearchConfiguration("url", "key")
        val b = MeilisearchConfiguration("url", "key")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun inequality() {
        val a = MeilisearchConfiguration("url1", "key")
        val b = MeilisearchConfiguration("url2", "key")
        assertNotEquals(a, b)
    }

    @Test
    fun copy() {
        val config = MeilisearchConfiguration("url", "key")
        val copied = config.copy(apiKey = "newKey")
        assertEquals("newKey", copied.apiKey)
        assertEquals("url", copied.url)
    }

    @Test
    fun indexPrefixSeparatesPhysicalIndexes() {
        val config = MeilisearchConfiguration("url", "key", indexPrefix = "sitea_")
        assertEquals("sitea_profiles", config.indexUid("profiles"))
        assertEquals("profiles", MeilisearchConfiguration("url", "key").indexUid("profiles"))
        assertEquals("sitea_assistant", config.chatWorkspaceUid("assistant"))
        assertEquals("assistant", MeilisearchConfiguration("url", "key").chatWorkspaceUid("assistant"))
        assertTrue(config.hasValidIndexPrefix)
        assertTrue(MeilisearchConfiguration("url", "key").hasValidIndexPrefix)
        // An invalid prefix is reported rather than rejected so it cannot prevent startup.
        assertFalse(MeilisearchConfiguration("url", "key", indexPrefix = "bad/prefix").hasValidIndexPrefix)
    }
}

class SearchConfigurationProviderTest {
    private fun application(yaml: String): BoscaApplication {
        val application = mockk<BoscaApplication>()
        every { application.environment } returns BoscaApplication.Environment(ApplicationConfig.load(yaml.byteInputStream()))
        return application
    }

    @Test
    fun `provider keeps an invalid prefix so requests fail instead of reaching unprefixed indexes`() {
        val configuration = SearchConfiguration().searchClientConfiguration(
            application("meilisearch:\n  url: http://meili\n  apiKey: key\n  indexPrefix: bad/prefix"),
        )
        assertEquals("bad/prefix", configuration.indexPrefix)
        assertFalse(configuration.hasValidIndexPrefix)
    }

    @Test
    fun `provider defaults to unprefixed indexes for existing installations`() {
        val configuration = SearchConfiguration().searchClientConfiguration(
            application("meilisearch:\n  url: http://meili\n  apiKey: key"),
        )
        assertEquals("", configuration.indexPrefix)
        assertEquals("profiles", configuration.indexUid("profiles"))
    }
}

class ExperimentalConfigurationTest {

    @Test
    fun defaultChatCompletionsIsFalse() {
        val config = ExperimentalConfiguration()
        assertFalse(config.chatCompletions)
    }

    @Test
    fun fieldPreservation() {
        val config = ExperimentalConfiguration(chatCompletions = true)
        assertEquals(true, config.chatCompletions)
    }

    @Test
    fun equality() {
        val a = ExperimentalConfiguration(false)
        val b = ExperimentalConfiguration(false)
        assertEquals(a, b)
    }

    @Test
    fun defaultEquality() {
        val a = ExperimentalConfiguration()
        val b = ExperimentalConfiguration(chatCompletions = false)
        assertEquals(a, b)
    }
}

class SearchJobQueueNamesTest {

    @Test
    fun indexJobQueueValue() {
        assertEquals("indexQueue", JobQueueNames.indexJobQueue)
    }

    @Test
    fun indexRunnerValue() {
        assertEquals("indexQueueRunner", JobQueueNames.indexRunner)
    }

    @Test
    fun indexQueueValue() {
        assertEquals("index", JobQueueNames.indexQueue)
    }

    @Test
    fun allConstantsAreDistinct() {
        val values = setOf(
            JobQueueNames.indexJobQueue,
            JobQueueNames.indexRunner,
            JobQueueNames.indexQueue
        )
        assertEquals(3, values.size)
    }
}
