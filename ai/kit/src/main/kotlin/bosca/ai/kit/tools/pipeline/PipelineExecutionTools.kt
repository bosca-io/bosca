package bosca.ai.kit.tools.pipeline

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.agents.pipeline.PipelineServices
import bosca.ai.kit.tools.KitTool
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Runs the engine's side-effect-free trace path through its existing GraphQL boundary. */
class DryRunPipelineTool(
    private val services: PipelineServices,
) : KitTool<DryRunPipelineTool.Input, DryRunPipelineTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "dry_run_pipeline",
    description = "Dry-run a saved pipeline as the current user. No action side effects execute; returns per-node outputs, proposed actions, errors, and skipped branches.",
) {
    @Serializable
    data class Input(
        val id: String,
        @property:LLMDescription("Event type FQDN for event-input pipelines; leave empty for JSON-input pipelines or to use the declared type.")
        val eventType: String = "",
        @property:LLMDescription("Sample input payload as JSON.")
        val payloadJson: String,
    )

    @Serializable
    data class Output(
        val success: Boolean,
        val outputs: JsonElement = JsonNull,
        val actions: JsonElement = JsonNull,
        val errors: JsonElement = JsonNull,
        val skipped: JsonElement = JsonNull,
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output = try {
        val payload = services.json.parseToJsonElement(input.payloadJson)
        val variables = buildJsonObject {
            put("id", input.id)
            put("eventType", input.eventType.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
            put("payload", payload)
        }
        val response = services.executeGraphQL(authentication, DRY_RUN_MUTATION, variables)
        response.graphQLError()?.let { return Output(success = false, error = it) }
        val result = response.graphQLData("pipelines", "dryRun") as? JsonObject
            ?: return Output(success = false, error = "Dry-run result was absent from the GraphQL response")
        val error = result.optionalString("error")
        Output(
            success = error == null,
            outputs = result.getValue("outputs"),
            actions = result.getValue("actions"),
            errors = result.getValue("errors"),
            skipped = result.getValue("skipped"),
            error = error,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Output(success = false, error = e.message ?: e.toString())
    }

    private companion object {
        val DRY_RUN_MUTATION = """
            mutation KitDryRunPipeline(${DOLLAR}id: UUID!, ${DOLLAR}eventType: String, ${DOLLAR}payload: JSON!) {
              pipelines {
                dryRun(id: ${DOLLAR}id, eventType: ${DOLLAR}eventType, payload: ${DOLLAR}payload) {
                  outputs actions errors skipped error
                }
              }
            }
        """.trimIndent()
        const val DOLLAR = '$'
    }
}

/** Starts a real durable manual run, only after explicit in-conversation confirmation. */
class RunPipelineTool(
    private val services: PipelineServices,
) : KitTool<RunPipelineTool.Input, RunPipelineTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "run_pipeline",
    description = "Run a pipeline for real as the current user. Action nodes have side effects and the run enters history. Use only after the user explicitly confirms this exact run.",
) {
    @Serializable
    data class Input(
        val id: String,
        @property:LLMDescription("Real input payload as JSON.")
        val payloadJson: String,
        @property:LLMDescription("True only when the user explicitly confirmed this exact real run in the conversation.")
        val confirmed: Boolean = false,
    )

    @Serializable
    data class Output(
        val success: Boolean,
        val runId: String = "",
        val status: String = "",
        val output: JsonElement = JsonNull,
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output = try {
        if (!input.confirmed) {
            return Output(
                success = false,
                error = "Real execution requires explicit user confirmation. Explain the side effects, obtain confirmation, then retry with confirmed=true.",
            )
        }
        val variables = buildJsonObject {
            put("id", input.id)
            put("payload", services.json.parseToJsonElement(input.payloadJson))
        }
        val response = services.executeGraphQL(authentication, RUN_MUTATION, variables)
        response.graphQLError()?.let { return Output(success = false, error = it) }
        val result = response.graphQLData("pipelines", "run") as? JsonObject
            ?: return Output(success = false, error = "Run result was absent from the GraphQL response")
        Output(
            success = result.boolean("ok"),
            runId = result.string("runId"),
            status = result.string("status"),
            output = result["output"] ?: JsonNull,
            error = result.optionalString("error"),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Output(success = false, error = e.message ?: e.toString())
    }

    private companion object {
        val RUN_MUTATION = """
            mutation KitRunPipeline(${DOLLAR}id: UUID!, ${DOLLAR}payload: JSON!) {
              pipelines {
                run(id: ${DOLLAR}id, payload: ${DOLLAR}payload) { ok runId status output error }
              }
            }
        """.trimIndent()
        const val DOLLAR = '$'
    }
}
