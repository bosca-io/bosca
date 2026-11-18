package bosca.system.ui

import bosca.system.ui.generated.SystemBibleComponent

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.system.ui.graphql.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class BibleActionsTest {
    private val configs = """{"content":{"bibleProviders":{"providers":["API_BIBLE"],"configurations":[{"id":"config","name":"Provider","provider":"API_BIBLE","enabled":true,"hasApiKey":true}]}}}"""
    private val source = """{"id":"source","configurationId":"config","name":"Bible","kind":"TEXT","metadataId":"metadata","metadataVersion":1,"variant":"reader","version":7,"allowedUses":[],"attribution":{"copyright":"Attribution","citation":"Citation","informationUrl":null}}"""
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun parse(value: String) = Json.parseToJsonElement(value)
    private fun result(field: String, value: String) = parse("""{"content":{"bibleProviders":{"$field":$value}}}""")
    private fun model() = SystemBibleModel(admin = true,
        providers = listOf("API_BIBLE"), configurationId = "config",
        configurations = SystemBibleConfigurations.decodeData(parse(configs)).content.bibleProviders.configurations)
    private fun entry(kind: String) = SystemBibleCatalog.decodeData(result("catalog", """[{"id":"edition","kind":"$kind","name":"Bible","nameLocal":"Bible","abbreviation":"B","description":"Description","languages":[{"name":"English","iso":"eng"}],"attribution":{"copyright":"Attribution","citation":"Citation","informationUrl":null}}]""")).content.bibleProviders.catalog.single()

    @Test fun `configuration updates retain keys and successful saves discard typed secrets`() = runTest {
        val model = model()
        model.editConfiguration(false)
        model.saveConfiguration(" Renamed ", "API_BIBLE", false, "", context { operation, variables ->
            when (operation) {
                "SystemSaveBibleConfiguration" -> {
                    val input = variables.getValue("input").jsonObject
                    assertTrue(input["apiKey"] == null || input["apiKey"] == JsonNull)
                    assertEquals("config", input.getValue("id").jsonPrimitive.content)
                    result("saveConfiguration", """{"id":"config","name":"Renamed","provider":"API_BIBLE","enabled":false,"hasApiKey":true}""")
                }
                "SystemBibleConfigurations" -> parse(configs)
                "SystemBibleSources" -> result("sources", "[]")
                else -> error(operation)
            }
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals("", model.draft.apiKey)
        assertTrue(model.needsCatalog)
        model.editConfiguration(true)
        model.saveConfiguration("New", "API_BIBLE", true, "", context { _, _ -> error("Must not write") })
        assertEquals("Enter the provider API key.", model.feedback.message)
        model.saveConfiguration("New", "API_BIBLE", true, "draft-secret", context { _, _ -> error("Denied") })
        assertEquals("draft-secret", model.draft.apiKey)
        assertEquals("configuration", model.mode)
        model.cancel(); assertEquals("", model.draft.apiKey)
    }

    @Test fun `catalog requests preserve filters and use offset pages without automatic failure loops`() = runTest {
        val model = model()
        assertTrue(model.needsCatalog)
        model.loadCatalog("AUDIO", " English ", 20, context { operation, variables ->
            assertEquals("SystemBibleCatalog", operation)
            assertEquals("English", variables.getValue("query").jsonPrimitive.content)
            assertEquals(20, variables.getValue("offset").jsonPrimitive.int)
            assertEquals(20, variables.getValue("limit").jsonPrimitive.int)
            error("Provider unavailable")
        })
        assertTrue(model.catalogFeedback.failed)
        assertFalse(model.needsCatalog)
        assertEquals(20, model.offset)
        assertEquals(" English ", model.search)
        model.loadCatalog("TEXT", "", -20, context { _, variables ->
            assertEquals(0, variables.getValue("offset").jsonPrimitive.int)
            result("catalog", "[]")
        })
        assertFalse(model.catalogFeedback.failed)
    }

    @Test fun `text registration accepts no granted uses and retains invalid drafts`() = runTest {
        val model = model().apply { catalog = listOf(entry("TEXT")) }
        model.prepareRegistration("edition")
        val noQuery = context { _, _ -> error("Must not write") }
        model.register("reader", "[\"PLAYBACK\"]", "", noQuery)
        assertEquals("Select uses appropriate for this source.", model.feedback.message)
        assertEquals("reader", model.variant)
        model.register("", "[]", "", noQuery)
        assertEquals("Enter a Bible variant.", model.feedback.message)
        model.register(" reader ", "[]", "", context { operation, variables ->
            if (operation == "SystemAddProviderBible") {
                val input = variables.getValue("input").jsonObject
                assertEquals("reader", input.getValue("variant").jsonPrimitive.content)
                assertTrue(input.getValue("allowedUses").jsonArray.isEmpty())
                result("addBible", """{"metadataId":"metadata","version":1,"variant":"reader"}""")
            } else result("sources", "[$source]")
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        assertNull(model.registration)
        assertEquals("", model.mode)
        assertEquals(1, model.sources.size)
    }

    @Test fun `audio registration requires a returned compatible variant and only playback permission`() = runTest {
        val model = model().apply { catalog = listOf(entry("AUDIO")) }
        model.prepareRegistration("edition")
        assertTrue(model.needsTargets)
        model.loadTargets(context { _, _ -> result("audioTargets", """[{"metadataId":"metadata","version":2,"variant":"reader","name":"Bible","enabled":false}]""") })
        assertFalse(model.needsTargets)
        model.register("", "[]", "invented-target", context { _, _ -> error("Must not write") })
        assertEquals("Select a compatible Bible variant.", model.feedback.message)
        model.register("", "[\"PLAYBACK\"]", "metadata:2:reader", context { operation, variables ->
            if (operation == "SystemAddProviderAudio") {
                assertEquals("metadata", variables.getValue("bibleId").jsonPrimitive.content)
                assertEquals(2, variables.getValue("version").jsonPrimitive.int)
                assertEquals("PLAYBACK", variables.getValue("input").jsonObject.getValue("allowedUses").jsonArray.single().jsonPrimitive.content)
                result("addAudio", """{"id":"audio"}""")
            } else result("sources", "[]")
        })
        assertFalse(model.feedback.failed, model.feedback.message)
    }

    @Test fun `removal retains the reviewed source version after a conflict and requires confirmation`() = runTest {
        val model = model().apply { sources = SystemBibleSources.decodeData(result("sources", "[$source]")).content.bibleProviders.sources }
        model.prepareRemoveSource("source")
        model.removeSource(false, context { _, _ -> error("Must not write") })
        assertEquals("Confirm source removal.", model.feedback.message)
        model.removeSource(true, context { operation, variables ->
            assertEquals("SystemRemoveBibleSource", operation)
            assertEquals(7, variables.getValue("expectedVersion").jsonPrimitive.int)
            error("Version conflict")
        })
        assertEquals(7L, model.removingSource?.version)
        assertEquals("source", model.mode)
        assertEquals("Version conflict", model.feedback.message)
    }

    @Test fun `configuration removal snapshots sources and requires explicit purge`() = runTest {
        val model = model()
        model.prepareRemoveConfiguration(context { _, _ -> result("sources", "[$source]") })
        assertEquals(1, model.removingSources.size)
        model.removeConfiguration(true, false, context { _, _ -> error("Must not write") })
        assertEquals("Confirm removal of all listed sources.", model.feedback.message)
        model.removeConfiguration(true, true, context { operation, variables ->
            assertEquals("SystemRemoveBibleConfiguration", operation)
            assertTrue(variables.getValue("purgeSources").jsonPrimitive.boolean)
            error("Concurrent source change")
        })
        assertTrue(model.purgeSources)
        assertEquals("removeConfiguration", model.mode)
        assertEquals(1, model.removingSources.size)
    }

    @Test fun `empty configuration selection stays quiet and populated invalid selections fail`() = runTest {
        val model = SystemBibleModel(admin = true, providers = listOf("API_BIBLE"))
        val noQuery = context { _, _ -> error("Must not query") }
        model.selectConfiguration("", noQuery)
        assertFalse(model.feedback.failed)
        assertEquals("", model.feedback.message)
        assertEquals(0, model.feedback.noticeSequence)
        val ctx = RenderContext()
        SystemBibleComponent.render(ctx, mapOf("initial" to model)) {}
        assertTrue(Regex("<select[^>]*name=\"configurationId\"[^>]*disabled").containsMatchIn(ctx.writer.toString()))
        assertTrue("Add a configuration to browse the provider catalog." in ctx.writer.toString())
        val populated = model()
        populated.selectConfiguration("", noQuery)
        assertTrue(populated.feedback.failed)
        assertEquals("Select a saved configuration.", populated.feedback.message)
        assertEquals("config", populated.configurationId)
    }

    @Test fun `provider cancellation propagates`() = runTest {
        val model = model()
        assertFailsWith<CancellationException> {
            model.loadCatalog("TEXT", "", 0, context { _, _ -> throw CancellationException("Cancelled") })
        }
    }
}
