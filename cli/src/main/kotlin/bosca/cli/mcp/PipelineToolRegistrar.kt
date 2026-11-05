package bosca.cli.mcp

import bosca.cli.api.PipelineApi
import bosca.graphql.gen.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/** Pipeline authoring and durable execution using the same domain API as Studio. */
object PipelineToolRegistrar {
    val tool = Tool(
        name = "bosca_pipeline",
        description = "Discover, inspect, validate, save, dry-run and execute reusable Bosca pipelines. " +
            "catalog supplies real node keys, settings, slots and value types; use those keys verbatim. " +
            "save takes a pipeline definition patch; read before editing and pass expectedVersion. Omitted fields are preserved; null clears optional fields. " +
            "New pipelines must be inactive. Activating triggers, an endpoint, public access or a schedule requires the user's explicit authorization and confirmedActivation=true. " +
            "Validation assigns editor coordinates. dry_run returns proposed actions without executing them. " +
            "run requires authorization for this exact pipeline and payload and confirmed=true. Never automatically retry an uncertain run; inspect its runId with run_status. " +
            "RUNNING and SUSPENDED are unfinished, not successful completion. Confirmation flags record the caller's assertion; a dry run is not approval.",
        inputSchema = ToolSchema(properties = buildJsonObject {
            putJsonObject("action") { put("type", "string"); put("enum", JsonArray(listOf("list", "get", "catalog", "validate", "save", "dry_run", "run", "run_status").map(::JsonPrimitive))) }
            for (name in listOf("id", "key", "search", "eventType", "runId")) putJsonObject(name) { put("type", "string") }
            putJsonObject("pipeline") { put("type", "object"); put("description", "Definition fields: name, description, acceptedInputType, graph, tags, key, triggered, api, public, schedule, maxConcurrentRuns, maxRunsPerMinute") }
            putJsonObject("graph") { put("type", "object"); put("description", "Complete graph for validate: nodes, edges, optional groups") }
            putJsonObject("payload") { put("description", "Input JSON value for dry_run or run") }
            for (name in listOf("confirmed", "confirmedActivation", "preview")) putJsonObject(name) { put("type", "boolean") }
            putJsonObject("expectedVersion") { put("type", "integer"); put("minimum", 0) }
            putJsonObject("offset") { put("type", "integer"); put("minimum", 0) }
            putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", 100) }
        }, required = listOf("action")),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true),
    )

    fun registerAll(server: Server, api: PipelineApi) {
        server.addTool(tool) { request -> handle(api, request.arguments ?: error("Missing arguments")) }
    }

    internal suspend fun handle(api: PipelineApi, args: Map<String, JsonElement>): CallToolResult = try {
        val content = mutableListOf<ContentBlock>()
        val text = when (args.required("action")) {
            "list" -> {
                val offset = args["offset"]?.jsonPrimitive?.int ?: 0
                val limit = args["limit"]?.jsonPrimitive?.int ?: 20
                require(offset >= 0 && limit in 1..100) { "Use a nonnegative offset and a limit between 1 and 100" }
                Json.encodeToString(ListManagedPipelinesData.serializer(), api.list(offset, limit, args.string("search")))
            }
            "get" -> {
                val pipeline = find(api, args)
                content += link(pipeline.id, pipeline.name)
                JsonObject(pipeline.fields() + mapOf("id" to JsonPrimitive(pipeline.id.toString()), "version" to JsonPrimitive(pipeline.version))).toString()
            }
            "catalog" -> Json.encodeToString(GetPipelineCatalogData.serializer(), api.catalog())
            "validate" -> Json.encodeToString(ValidateManagedPipelineData.serializer(), api.validate(requireNotNull(args["graph"]) { "Missing graph" }))
            "save" -> {
                val patch = args["pipeline"]?.jsonObject ?: error("Missing pipeline definition")
                require("id" !in patch && "version" !in patch) { "Pass id and expectedVersion outside the pipeline definition" }
                val previous = if (args.string("id") != null || args.string("key") != null) find(api, args) else null
                val version = if (previous != null) args["expectedVersion"]?.jsonPrimitive?.int ?: error("Missing expectedVersion; read before editing") else null
                require(previous == null || version == previous.version) { "Pipeline changed; read it again and reapply the edit" }
                val fields = buildJsonObject {
                    if (previous != null) previous.fields().forEach { (key, value) -> put(key, value) }
                    else { put("description", ""); put("triggered", false); put("api", false); put("public", false) }
                    patch.forEach { (key, value) -> put(key, value) }
                    previous?.let { put("id", it.id.toString()); put("version", requireNotNull(version)) }
                }
                val definition = Json.decodeFromJsonElement(PipelineInput.serializer(), fields)
                require(definition.name.isNotBlank() && definition.acceptedInputType.isNotBlank()) { "name and acceptedInputType must not be blank" }
                val active = definition.triggered == true || definition.api == true || definition.public == true || !definition.schedule.isNullOrBlank()
                require(previous != null || !active) { "Create pipelines inactive, then dry-run and explicitly authorize activation" }
                val activates = previous != null && ((!previous.triggered && definition.triggered == true) ||
                    (!previous.api && definition.api == true) || (!previous.public && definition.public == true) ||
                    (!definition.schedule.isNullOrBlank() && definition.schedule != previous.schedule) ||
                    (definition.api == true && definition.key != previous.key))
                require(!activates || args.bool("preview") || args.bool("confirmedActivation")) { "Activation requires explicit user authorization and confirmedActivation=true" }
                val validation = api.validate(definition.graph).pipelines.validateGraph
                require(validation.valid) { "Graph is invalid and was not saved: ${validation.errors.joinToString("; ")}" }
                val laidOut = definition.copy(graph = validation.graph)
                if (args.bool("preview")) buildJsonObject {
                    put("preview", true); put("pipeline", Json.encodeToJsonElement(PipelineInput.serializer(), laidOut))
                }.toString() else {
                    val saved = api.save(laidOut)
                    content += link(saved.pipelines.save.id, saved.pipelines.save.name)
                    Json.encodeToString(SaveManagedPipelineData.serializer(), saved)
                }
            }
            "dry_run" -> Json.encodeToString(DryRunManagedPipelineData.serializer(), api.dryRun(target(api, args), args.string("eventType"), requireNotNull(args["payload"]) { "Missing payload" }))
            "run" -> {
                require(args.bool("confirmed")) { "Real execution requires explicit user authorization for this pipeline and payload, and confirmed=true" }
                val result = api.run(target(api, args), requireNotNull(args["payload"]) { "Missing payload" })
                val run = result.pipelines.run
                if (run.runId != Uuid.NIL) content += ResourceLink(name = "Pipeline run", uri = "bosca://pipelines/runs/${run.runId}")
                Json.encodeToString(RunManagedPipelineData.serializer(), result)
            }
            "run_status" -> Json.encodeToString(GetManagedPipelineRunData.serializer(), api.runStatus(Uuid.parse(args.required("runId"))))
            else -> error("Unknown pipeline action")
        }
        CallToolResult(content = listOf(TextContent(text)) + content)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { CallToolResult(content = listOf(TextContent("Error: ${failure.message}")), isError = true) }

    private suspend fun find(api: PipelineApi, args: Map<String, JsonElement>): IManagedPipeline {
        require((args.string("id") != null) != (args.string("key") != null)) { "Supply exactly one pipeline id or key" }
        return requireNotNull(args.string("id")?.let { api.get(Uuid.parse(it)) } ?: args.string("key")?.let { api.get(it) }) { "Pipeline not found or unavailable" }
    }
    private suspend fun target(api: PipelineApi, args: Map<String, JsonElement>): Uuid {
        require((args.string("id") != null) != (args.string("key") != null)) { "Supply exactly one pipeline id or key" }
        return args.string("id")?.let(Uuid::parse) ?: find(api, args).id
    }
    private fun IManagedPipeline.fields() = buildJsonObject {
        put("name", name); put("description", description); put("acceptedInputType", acceptedInputType); put("graph", graph)
        put("tags", JsonArray(tags.map(::JsonPrimitive))); put("key", key); put("triggered", triggered); put("api", api); put("public", public)
        put("schedule", schedule?.let(::JsonPrimitive) ?: JsonNull)
        put("maxConcurrentRuns", maxConcurrentRuns?.let(::JsonPrimitive) ?: JsonNull)
        put("maxRunsPerMinute", maxRunsPerMinute?.let(::JsonPrimitive) ?: JsonNull)
    }
    private fun link(id: Uuid, name: String) = ResourceLink(name = name, uri = "bosca://pipelines/$id")
    private fun Map<String, JsonElement>.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun Map<String, JsonElement>.required(key: String) = requireNotNull(string(key)?.takeIf(String::isNotBlank)) { "Missing $key" }
    private fun Map<String, JsonElement>.bool(key: String) = this[key]?.jsonPrimitive?.boolean ?: false
}
