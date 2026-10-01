package bosca.ai.kit.tools.pipeline

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.agents.pipeline.PipelineServices
import bosca.ai.kit.tools.KitTool
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

/** Lists stored pipelines so Kit can avoid duplicates and understand existing automation. */
class ListPipelinesTool(
    private val services: PipelineServices,
) : KitTool<ListPipelinesTool.Input, ListPipelinesTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "list_pipelines",
    description = "List existing pipelines and their activation flags before creating or editing an automation.",
) {
    @Serializable
    class Input

    @Serializable
    data class PipelineSummary(
        val id: String,
        val key: String,
        val name: String,
        val description: String,
        val acceptedInputType: String,
        val version: Long,
        val triggered: Boolean,
        val api: Boolean,
        val public: Boolean,
        val schedule: String?,
    )

    @Serializable
    data class Output(
        val pipelines: List<PipelineSummary>,
        val count: Int,
        val success: Boolean,
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output = try {
        services.verifyCanManage(authentication)
        val pipelines = services.pipelineService.getAll().map { pipeline ->
            PipelineSummary(
                id = pipeline.id.toString(),
                key = pipeline.key,
                name = pipeline.name,
                description = pipeline.description,
                acceptedInputType = pipeline.acceptedInputType,
                version = pipeline.version,
                triggered = pipeline.triggered,
                api = pipeline.api,
                public = pipeline.public,
                schedule = pipeline.schedule,
            )
        }
        Output(pipelines, pipelines.size, success = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Output(emptyList(), 0, success = false, error = e.message ?: e.toString())
    }
}

/** Returns an editable pipeline graph in the engine's exact polymorphic JSON format. */
class GetPipelineTool(
    private val services: PipelineServices,
) : KitTool<GetPipelineTool.Input, GetPipelineTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "get_pipeline",
    description = "Get one pipeline by id or key, including its exact editable graph JSON and optimistic-lock version.",
) {
    @Serializable
    data class Input(
        @property:LLMDescription("Pipeline UUID. Supply either id or key.")
        val id: String = "",
        @property:LLMDescription("Stable pipeline key. Supply either key or id.")
        val key: String = "",
    )

    @Serializable
    data class Output(
        val found: Boolean,
        val id: String = "",
        val key: String = "",
        val name: String = "",
        val description: String = "",
        val acceptedInputType: String = "",
        val tags: List<String> = emptyList(),
        val version: Long = 0,
        val triggered: Boolean = false,
        val api: Boolean = false,
        val public: Boolean = false,
        val schedule: String? = null,
        val maxConcurrentRuns: Int? = null,
        val maxRunsPerMinute: Int? = null,
        val graphJson: String = "",
        val editorPath: String = "",
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output = try {
        services.verifyCanManage(authentication)
        val pipeline = when {
            input.id.isNotBlank() -> services.pipelineService.get(UUID.parse(input.id))
            input.key.isNotBlank() -> services.pipelineService.getByKey(input.key)
            else -> return Output(found = false, error = "Supply a pipeline id or key")
        } ?: return Output(found = false, error = "Pipeline not found")
        Output(
            found = true,
            id = pipeline.id.toString(),
            key = pipeline.key,
            name = pipeline.name,
            description = pipeline.description,
            acceptedInputType = pipeline.acceptedInputType,
            tags = pipeline.tags,
            version = pipeline.version,
            triggered = pipeline.triggered,
            api = pipeline.api,
            public = pipeline.public,
            schedule = pipeline.schedule,
            maxConcurrentRuns = pipeline.maxConcurrentRuns,
            maxRunsPerMinute = pipeline.maxRunsPerMinute,
            graphJson = services.pipelineService.graphAsJsonElement(pipeline).toString(),
            editorPath = "/pipelines/${pipeline.id}",
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Output(found = false, error = e.message ?: e.toString())
    }
}

/** Reads the loaded node catalog through the same admin-gated GraphQL resolver used by Studio. */
class ListPipelineNodeTypesTool(
    private val services: PipelineServices,
) : KitTool<ListPipelineNodeTypesTool.Input, ListPipelineNodeTypesTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "list_pipeline_node_types",
    description = "List every registered pipeline node type. Use each key verbatim as the node JSON 'type' discriminator; obey its input/output slot kinds and settings metadata.",
) {
    @Serializable
    class Input

    @Serializable
    data class FieldSummary(val name: String, val type: String)

    @Serializable
    data class InputSlot(
        val name: String,
        val kind: String,
        val typeLabel: String,
        val description: String? = null,
        val type: String? = null,
        val schema: JsonElement? = null,
        val required: Boolean,
        val structure: List<FieldSummary>? = null,
    )

    @Serializable
    data class OutputSlot(
        val name: String,
        val kind: String,
        val error: Boolean,
        val type: String? = null,
        val typeLabel: String? = null,
        val description: String? = null,
        val structure: List<FieldSummary>? = null,
    )

    @Serializable
    data class SettingOption(val value: String, val label: String? = null)

    @Serializable
    data class SettingField(
        val name: String,
        val control: String,
        val label: String? = null,
        val description: String? = null,
        val default: String? = null,
        val required: Boolean,
        val secret: Boolean,
        val mono: Boolean,
        val language: String? = null,
        val reference: String? = null,
        val options: List<SettingOption> = emptyList(),
    )

    @Serializable
    data class Setting(
        val name: String,
        val control: String,
        val label: String? = null,
        val description: String? = null,
        val placeholder: String? = null,
        val default: String? = null,
        val required: Boolean,
        val secret: Boolean,
        val mono: Boolean,
        val language: String? = null,
        val reference: String? = null,
        val options: List<SettingOption> = emptyList(),
        val fields: List<SettingField> = emptyList(),
        val itemLabel: String? = null,
        val group: String? = null,
        val visibleWhenSetting: String? = null,
        val visibleWhenEquals: String? = null,
    )

    @Serializable
    data class NodeType(
        val key: String,
        val label: String,
        val category: String,
        val group: String? = null,
        val subgroup: String? = null,
        val description: String,
        val inputs: List<InputSlot>,
        val outputs: List<OutputSlot>,
        val settings: List<Setting>,
    )

    @Serializable
    data class Output(
        val nodeTypes: List<NodeType>,
        val count: Int,
        val success: Boolean,
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output = try {
        val response = services.executeGraphQL(authentication, QUERY)
        response.graphQLError()?.let { return Output(emptyList(), 0, success = false, error = it) }
        val data = response.graphQLData("pipelines", "nodeTypes")
            ?: return Output(emptyList(), 0, success = false, error = "Pipeline node catalog was absent from the GraphQL response")
        val nodeTypes = services.json.decodeFromJsonElement(ListSerializer(NodeType.serializer()), data)
        Output(nodeTypes, nodeTypes.size, success = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Output(emptyList(), 0, success = false, error = e.message ?: e.toString())
    }

    private companion object {
        val QUERY = """
            query KitPipelineNodeTypes {
              pipelines {
                nodeTypes {
                  key label category group subgroup description
                  inputs { name kind typeLabel description type schema required structure { name type } }
                  outputs { name kind error type typeLabel description structure { name type } }
                  settings {
                    name control label description placeholder default required secret mono language reference
                    options { value label }
                    fields { name control label description default required secret mono language reference options { value label } }
                    itemLabel group visibleWhenSetting visibleWhenEquals
                  }
                }
              }
            }
        """.trimIndent()
    }
}
