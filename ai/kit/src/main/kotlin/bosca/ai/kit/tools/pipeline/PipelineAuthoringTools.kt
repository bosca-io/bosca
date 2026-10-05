package bosca.ai.kit.tools.pipeline

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.agents.pipeline.PipelineServices
import bosca.ai.kit.tools.KitTool
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Validates a draft with the engine's typed decoder and connection rules without persisting it. */
class ValidatePipelineGraphTool(
    private val services: PipelineServices,
) : KitTool<ValidatePipelineGraphTool.Input, ValidatePipelineGraphTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "validate_pipeline_graph",
    description = "Validate graph JSON with the pipeline engine. Return the engine violation verbatim, repair the draft, and re-validate. Never save a graph that is not valid.",
) {
    @Serializable
    data class Input(
        @property:LLMDescription("Complete graph JSON: {nodes:[...],edges:[...],groups?:[...]} using catalog node keys verbatim.")
        val graphJson: String,
    )

    @Serializable
    data class Output(
        val valid: Boolean,
        val violations: List<String> = emptyList(),
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output = try {
        services.verifyCanManage(authentication)
        val graph = services.json.parseToJsonElement(input.graphJson)
        val violation = services.pipelineService.validateGraph(graph)
        if (violation == null) Output(valid = true) else Output(valid = false, violations = listOf(violation))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Output(valid = false, error = e.message ?: e.toString())
    }
}

/** Saves a validated graph through GraphQL, enforcing inactive creation and explicit activation. */
class SavePipelineTool(
    private val services: PipelineServices,
) : KitTool<SavePipelineTool.Input, SavePipelineTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "save_pipeline",
    description = "Create or update a pipeline after validation. New pipelines are always inactive. Enabling triggers, API/public access, or a schedule on an update requires explicit user confirmation. Coordinates are assigned deterministically.",
) {
    @Serializable
    data class Input(
        @property:LLMDescription("Existing pipeline UUID to update; leave empty to create.")
        val id: String = "",
        val name: String,
        val description: String = "",
        val acceptedInputType: String,
        val tags: List<String> = emptyList(),
        @property:LLMDescription("Complete graph JSON. Do not invent positions; this tool lays it out.")
        val graphJson: String,
        @property:LLMDescription("Current optimistic-lock version from get_pipeline; use 0 for create.")
        val version: Long = 0,
        val key: String = "",
        val triggered: Boolean = false,
        val api: Boolean = false,
        val public: Boolean = false,
        val schedule: String? = null,
        val maxConcurrentRuns: Int? = null,
        val maxRunsPerMinute: Int? = null,
        @property:LLMDescription("True only when the user explicitly confirmed the activation change in this conversation.")
        val confirmedActivation: Boolean = false,
    )

    @Serializable
    data class Output(
        val success: Boolean,
        val id: String = "",
        val key: String = "",
        val name: String = "",
        val version: Long = 0,
        val triggered: Boolean = false,
        val api: Boolean = false,
        val public: Boolean = false,
        val schedule: String? = null,
        val graphJson: String = "",
        val editorPath: String = "",
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output = try {
        services.verifyCanManage(authentication)
        val existing = if (input.id.isBlank()) null else services.pipelineService.get(UUID.parse(input.id))
            ?: return Output(success = false, error = "Pipeline not found: ${input.id}. Re-fetch before editing.")

        if (existing == null && input.requestsActivation()) {
            return Output(
                success = false,
                error = "New pipelines must be created inactive (triggered=false, api=false, public=false, schedule=null). Create it, dry-run it, then request activation in a separate confirmed update.",
            )
        }
        if (existing != null && input.activatesComparedWith(existing) && !input.confirmedActivation) {
            return Output(
                success = false,
                error = "Activation requires explicit user confirmation. Restate what will run automatically or become callable, obtain confirmation, then retry with confirmedActivation=true.",
            )
        }

        val draft = services.json.parseToJsonElement(input.graphJson)
        services.pipelineService.validateGraph(draft)?.let { violation ->
            return Output(success = false, error = "Graph is invalid and was not saved: $violation")
        }
        val graph = PipelineGraphLayout.layout(draft)
        val response = services.executeGraphQL(authentication, SAVE_MUTATION, buildVariables(input, graph))
        response.graphQLError()?.let { return Output(success = false, error = conflictGuidance(it)) }
        val saved = response.graphQLData("pipelines", "save") as? JsonObject
            ?: return Output(success = false, error = "Saved pipeline was absent from the GraphQL response")
        val id = saved.string("id")
        Output(
            success = true,
            id = id,
            key = saved.string("key"),
            name = saved.string("name"),
            version = saved.long("version"),
            triggered = saved.boolean("triggered"),
            api = saved.boolean("api"),
            public = saved.boolean("public"),
            schedule = saved.optionalString("schedule"),
            graphJson = graph.toString(),
            editorPath = "/pipelines/$id",
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Output(success = false, error = conflictGuidance(e.message ?: e.toString()))
    }

    private fun Input.requestsActivation(): Boolean = triggered || api || public || !schedule.isNullOrBlank()

    private fun Input.activatesComparedWith(existing: bosca.pipelines.model.Pipeline): Boolean =
        (!existing.triggered && triggered) ||
            (!existing.api && api) ||
            (!existing.public && public) ||
            (schedule != existing.schedule && !schedule.isNullOrBlank())

    private fun buildVariables(input: Input, graph: kotlinx.serialization.json.JsonElement): JsonObject = buildJsonObject {
        putJsonObject("input") {
            if (input.id.isNotBlank()) put("id", input.id)
            put("name", input.name)
            put("description", input.description)
            put("acceptedInputType", input.acceptedInputType)
            put("tags", buildJsonArray { input.tags.forEach { add(JsonPrimitive(it)) } })
            put("triggered", input.triggered)
            put("key", input.key)
            put("api", input.api)
            put("public", input.public)
            put("schedule", input.schedule?.let(::JsonPrimitive) ?: JsonNull)
            put("maxConcurrentRuns", input.maxConcurrentRuns?.let(::JsonPrimitive) ?: JsonNull)
            put("maxRunsPerMinute", input.maxRunsPerMinute?.let(::JsonPrimitive) ?: JsonNull)
            put("version", JsonPrimitive(input.version))
            put("graph", graph)
        }
    }

    private fun conflictGuidance(message: String): String =
        if (message.contains("version", ignoreCase = true) || message.contains("optimistic", ignoreCase = true)) {
            "$message Re-fetch with get_pipeline and re-apply the intended change; do not retry the stale version blindly."
        } else {
            message
        }

    private companion object {
        val SAVE_MUTATION = """
            mutation KitSavePipeline(${DOLLAR}input: PipelineInput!) {
              pipelines {
                save(input: ${DOLLAR}input) { id key name version triggered api public schedule }
              }
            }
        """.trimIndent()

        const val DOLLAR = '$'
    }
}
