package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.*
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid

/** Reusable pipeline operations through the caller's authenticated GraphQL connection. */
class PipelineApi(network: NetworkClient) : Api(network) {
    suspend fun list(offset: Int, limit: Int, search: String?) = network.boscaGraphql.execute(ListManagedPipelines, ListManagedPipelines.Variables(offset, limit, search))
    suspend fun get(id: Uuid) = network.boscaGraphql.execute(GetManagedPipeline, GetManagedPipeline.Variables(id)).pipelines.pipeline
    suspend fun get(key: String) = network.boscaGraphql.execute(GetManagedPipelineByKey, GetManagedPipelineByKey.Variables(key)).pipelines.pipelineByKey
    suspend fun catalog() = network.boscaGraphql.execute(GetPipelineCatalog, Unit)
    suspend fun validate(graph: JsonElement) = network.boscaGraphql.execute(ValidateManagedPipeline, ValidateManagedPipeline.Variables(graph))
    suspend fun save(input: PipelineInput) = network.boscaGraphql.execute(SaveManagedPipeline, SaveManagedPipeline.Variables(input))
    suspend fun dryRun(id: Uuid, eventType: String?, payload: JsonElement) = network.boscaGraphql.execute(DryRunManagedPipeline, DryRunManagedPipeline.Variables(id, eventType, payload))
    suspend fun run(id: Uuid, payload: JsonElement) = network.boscaGraphql.execute(RunManagedPipeline, RunManagedPipeline.Variables(id, payload))
    suspend fun runStatus(id: Uuid) = network.boscaGraphql.execute(GetManagedPipelineRun, GetManagedPipelineRun.Variables(id))
}
