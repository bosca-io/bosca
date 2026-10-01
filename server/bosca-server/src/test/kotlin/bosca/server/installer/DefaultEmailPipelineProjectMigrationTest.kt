package bosca.server.installer

import bosca.pipelines.model.Pipeline
import bosca.pipelines.service.PipelineService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultEmailPipelineProjectMigrationTest {

    @Test
    fun `migration changes only legacy project references`() {
        val graph = legacyGraph()

        val migrated = requireNotNull(migrateDefaultEmailPipelineGraph(graph)).jsonObject
        val nodes = migrated.getValue("nodes").jsonArray.map { it.jsonObject }

        assertEquals("bosca-messages", nodes[0].getValue("project").jsonPrimitive.content)
        assertEquals("bosca-messages/welcome", nodes[0].getValue("template").jsonPrimitive.content)
        assertEquals("operator description mentioning bosca-emails", nodes[0].getValue("description").jsonPrimitive.content)
        assertEquals("email:bosca-messages/welcome", nodes[1].getValue("outputType").jsonPrimitive.content)
        assertEquals(graph.jsonObject.getValue("edges"), migrated.getValue("edges"))
        assertEquals(graph.jsonObject.getValue("groups"), migrated.getValue("groups"))
    }

    @Test
    fun `migration leaves current and unrelated graphs alone`() {
        val current = JsonObject(
            legacyGraph().jsonObject + (
                "nodes" to JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "sendEmailTemplate")
                            put("project", "customer-messages")
                            put("template", "welcome")
                        },
                        buildJsonObject {
                            put("type", "jsonata")
                            put("outputType", "email:customer-messages/welcome")
                        },
                    ),
                )
            ),
        )

        assertNull(migrateDefaultEmailPipelineGraph(current))
        assertNull(migrateDefaultEmailPipelineGraph(JsonPrimitive("not a graph")))
    }

    @Test
    fun `stored pipeline update preserves every editable metadata field`() = runTest {
        val pipeline = Pipeline(
            id = UUID.random(),
            name = "Email Account Activity — Welcome",
            description = "Operator description",
            acceptedInputType = "example.Event",
            tags = listOf("Email", "Customized"),
            triggered = false,
            key = "welcome-email",
            api = true,
            public = true,
            schedule = "0 7 * * *",
            maxConcurrentRuns = 2,
            maxRunsPerMinute = 12,
            version = 7,
        )
        val service = mockk<PipelineService>()
        val graph = legacyGraph()
        coEvery { service.graphAsJsonElement(pipeline) } returns graph
        coEvery {
            service.save(
                id = pipeline.id,
                name = pipeline.name,
                description = pipeline.description,
                acceptedInputType = pipeline.acceptedInputType,
                tags = pipeline.tags,
                triggered = pipeline.triggered,
                key = pipeline.key,
                api = pipeline.api,
                public = pipeline.public,
                schedule = pipeline.schedule,
                maxConcurrentRuns = pipeline.maxConcurrentRuns,
                maxRunsPerMinute = pipeline.maxRunsPerMinute,
                version = pipeline.version,
                graph = any(),
            )
        } returns pipeline

        assertEquals(true, service.migrateDefaultEmailPipeline(pipeline))

        coVerify(exactly = 1) {
            service.save(
                id = pipeline.id,
                name = pipeline.name,
                description = pipeline.description,
                acceptedInputType = pipeline.acceptedInputType,
                tags = pipeline.tags,
                triggered = pipeline.triggered,
                key = pipeline.key,
                api = pipeline.api,
                public = pipeline.public,
                schedule = pipeline.schedule,
                maxConcurrentRuns = pipeline.maxConcurrentRuns,
                maxRunsPerMinute = pipeline.maxRunsPerMinute,
                version = pipeline.version,
                graph = match { migrated ->
                    migrated.jsonObject.getValue("nodes").jsonArray[0].jsonObject
                        .getValue("project").jsonPrimitive.content == "bosca-messages"
                },
            )
        }
    }

    private fun legacyGraph() = buildJsonObject {
        put("nodes", JsonArray(listOf(
            buildJsonObject {
                put("type", "sendEmailTemplate")
                put("id", "send")
                put("project", "bosca-emails")
                put("template", "bosca-emails/welcome")
                put("description", "operator description mentioning bosca-emails")
            },
            buildJsonObject {
                put("type", "jsonata")
                put("id", "payload")
                put("outputType", "email:bosca-emails/welcome")
                put("expression", "payload")
            },
        )))
        put("edges", JsonArray(listOf(buildJsonObject { put("id", "edge") })))
        put("groups", JsonArray(listOf(buildJsonObject { put("id", "group") })))
    }
}
