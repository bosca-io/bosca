package bosca.cli.a2a

import ai.koog.a2a.model.AgentCapabilities
import ai.koog.a2a.model.AgentCard
import ai.koog.a2a.model.AgentSkill
import ai.koog.a2a.model.Artifact
import ai.koog.a2a.model.DataPart
import ai.koog.a2a.model.FilePart
import ai.koog.a2a.model.FileWithBytes
import ai.koog.a2a.model.HTTPAuthSecurityScheme
import ai.koog.a2a.model.Message
import ai.koog.a2a.model.MessageSendParams
import ai.koog.a2a.model.Role
import ai.koog.a2a.model.Task
import ai.koog.a2a.model.TaskArtifactUpdateEvent
import ai.koog.a2a.model.TaskState
import ai.koog.a2a.model.TaskStatus
import ai.koog.a2a.model.TextPart
import ai.koog.a2a.model.TransportProtocol
import ai.koog.a2a.server.agent.AgentExecutor
import ai.koog.a2a.server.session.RequestContext
import ai.koog.a2a.server.session.SessionEventProcessor
import bosca.cli.Version
import bosca.cli.api.KitApi
import bosca.cli.api.KitChatTurn
import bosca.cli.api.KitResponse
import bosca.cli.api.KitTurnControl
import bosca.cli.api.KitTurnCancelledBeforeSubmission
import ai.koog.a2a.exceptions.A2ATaskNotCancelableException
import ai.koog.a2a.model.TaskIdParams
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import bosca.cli.api.responseContent
import bosca.cli.api.resultContent
import bosca.cli.api.summaryJson
import bosca.graphql.client.GraphQLJson
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.ToolCallContent
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Bridges A2A tasks to Kit's durable, server-side chat sessions. */
internal class KitA2AAgentExecutor(
    private val api: KitApi,
    private val timeoutMillis: Long = KitApi.DEFAULT_TIMEOUT_MILLIS,
) : AgentExecutor {

    private val sessions = ConcurrentHashMap<String, Uuid>()
    private val contextLocks = ConcurrentHashMap<String, Mutex>()
    private val sessionLocks = ConcurrentHashMap<Uuid, Mutex>()
    private val activeTurns = ConcurrentHashMap<String, KitTurnControl>()

    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor,
    ) {
        val userText = context.params.message.parts
            .filterIsInstance<TextPart>()
            .joinToString("\n", transform = TextPart::text)
            .trim()
        if (userText.isBlank()) {
            eventProcessor.sendInitialTask(context, TaskState.Rejected, "Kit requires a non-empty text message")
            return
        }

        val control = KitTurnControl()
        check(activeTurns.putIfAbsent(context.taskId, control) == null) { "Kit task is already running" }
        try {
            val workingMessage = eventProcessor.sendInitialTask(context, TaskState.Working, "Kit is working on the request")
            val turn = withContext(control) {
                contextLocks.computeIfAbsent(context.contextId) { Mutex() }.withLock {
                    ask(context, userText)
                }
            }
            val response = turn.responseContent()
            val metadata = sessionMetadata(turn)
            val artifacts = sendArtifacts(context, eventProcessor, turn, response, metadata)

            val failed = response.failed || response.empty
            val text = listOfNotNull(response.text.takeIf(String::isNotBlank), response.outcomeText)
                .joinToString("\n\n").ifBlank { if (failed) "Kit returned no response." else "Kit completed the request." }
            eventProcessor.sendFinalTask(
                context = context,
                state = when {
                    response.cancelled -> TaskState.Canceled
                    failed -> TaskState.Failed
                    else -> TaskState.Completed
                },
                text = text,
                metadata = metadata,
                artifacts = artifacts,
                history = listOf(workingMessage),
            )
        } catch (e: KitTurnCancelledBeforeSubmission) {
            eventProcessor.sendFinalTask(context, TaskState.Canceled, requireNotNull(e.message))
        } catch (e: CancellationException) {
            control.finish(e)
            throw e
        } catch (e: Exception) {
            eventProcessor.sendFinalTask(
                context,
                TaskState.Failed,
                "Kit request failed: ${e.message ?: "unknown error"}",
            )
        } finally {
            activeTurns.remove(context.taskId, control)
            control.finish()
        }
    }

    /** Forwards cancellation intent, leaving the execution path to publish the actual terminal outcome. */
    override suspend fun cancel(context: RequestContext<TaskIdParams>, eventProcessor: SessionEventProcessor, agentJob: Deferred<Unit>?) {
        val control = activeTurns[context.taskId] ?: throw A2ATaskNotCancelableException("Kit task is no longer running")
        control.requestCancellation()
        if (withTimeoutOrNull(5_000) { control.awaitFinished(); true } != true) {
            throw A2ATaskNotCancelableException("Cancellation requested; Bosca has not confirmed the outcome. Check the task again.")
        }
    }

    private suspend fun ask(context: RequestContext<MessageSendParams>, message: String): KitChatTurn {
        kotlinx.coroutines.currentCoroutineContext()[KitTurnControl]?.checkBeforeSubmission()
        val requestedSession = requestedSessionId(context)
        val sessionId = requestedSession ?: sessions[context.contextId] ?: api.createSession(defaultTitle(message)).id
        sessions[context.contextId] = sessionId
        return sessionLocks.computeIfAbsent(sessionId) { Mutex() }.withLock {
            api.ask(message = message, sessionId = sessionId, timeoutMillis = timeoutMillis).also { turn ->
                sessions[context.contextId] = turn.session.id
            }
        }
    }

    private fun requestedSessionId(context: RequestContext<MessageSendParams>): Uuid? {
        val raw = sequenceOf(context.params.metadata, context.params.message.metadata)
            .filterNotNull()
            .mapNotNull { metadata -> metadata[KIT_SESSION_METADATA]?.jsonPrimitive?.contentOrNull }
            .firstOrNull()
            ?: return null
        return Uuid.parse(raw)
    }

    private suspend fun sendArtifacts(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor,
        turn: KitChatTurn,
        response: KitResponse,
        metadata: JsonObject,
    ): List<Artifact> {
        val artifacts = mutableListOf<Artifact>()
        for (harness in response.harnessTurns) {
            val artifact = Artifact(
                artifactId = harness.turnId,
                name = "Kit turn result",
                parts = buildList {
                    add(DataPart(harness.summaryJson()))
                    harness.resultContent().forEach { (toolId, result) ->
                        val partMetadata = buildJsonObject {
                            put("boscaTurnId", harness.turnId)
                            toolId?.let { put("boscaToolCallId", it) }
                        }
                        val block = (result as? ToolCallContent.Content)?.content
                        add(when (block) {
                            is ContentBlock.Text -> TextPart(block.text, metadata = partMetadata)
                            is ContentBlock.Image -> FilePart(FileWithBytes(bytes = block.data, mimeType = block.mimeType), metadata =
                                buildJsonObject { partMetadata.forEach(::put); block.uri?.let { put("uri", it) } })
                            is ContentBlock.Audio -> FilePart(FileWithBytes(bytes = block.data, mimeType = block.mimeType), metadata = partMetadata)
                            is ContentBlock.ResourceLink -> DataPart(
                                GraphQLJson.encodeToJsonElement(ContentBlock.serializer(), block).jsonObject, metadata = partMetadata)
                            is ContentBlock.Resource -> when (val resource = block.resource) {
                                is EmbeddedResourceResource.BlobResourceContents -> FilePart(
                                    FileWithBytes(bytes = resource.blob, mimeType = resource.mimeType),
                                    metadata = buildJsonObject { partMetadata.forEach(::put); put("uri", resource.uri) })
                                is EmbeddedResourceResource.TextResourceContents -> DataPart(
                                    GraphQLJson.encodeToJsonElement(ContentBlock.serializer(), block).jsonObject, metadata = partMetadata)
                            }
                            else -> DataPart(GraphQLJson.encodeToJsonElement(ToolCallContent.serializer(), result).jsonObject, metadata = partMetadata)
                        })
                    }
                },
                metadata = metadata,
            )
            artifacts += artifact
            eventProcessor.sendTaskEvent(TaskArtifactUpdateEvent(taskId = context.taskId, contextId = context.contextId,
                artifact = artifact, append = false, lastChunk = true, metadata = metadata))
        }
        if (response.displayEvents.isNotEmpty()) {
            val artifact = Artifact(
                artifactId = "${context.taskId}-analytics",
                name = "Kit analytics result",
                description = "Structured analytics and GraphQL display events returned by Kit",
                parts = listOf(
                    DataPart(buildJsonObject { put("events", JsonArray(response.displayEvents)) }),
                ),
                metadata = metadata,
            )
            artifacts += artifact
            eventProcessor.sendTaskEvent(
                TaskArtifactUpdateEvent(
                    taskId = context.taskId,
                    contextId = context.contextId,
                    artifact = artifact,
                    append = false,
                    lastChunk = true,
                    metadata = metadata,
                ),
            )
        }

        for (reference in response.images) {
            val asset = api.downloadAsset(reference.metadataId)
            require(asset.contentType.startsWith("image/")) {
                "Metadata ${reference.metadataId} is ${asset.contentType}, not an image"
            }
            val artifact = Artifact(
                artifactId = reference.metadataId.toString(),
                name = reference.alt.ifBlank { "Kit-generated image" },
                description = "Image generated by Kit in chat session ${turn.session.id}",
                parts = listOf(
                    FilePart(
                        FileWithBytes(
                            bytes = Base64.getEncoder().encodeToString(asset.bytes),
                            name = "${reference.metadataId}.${extension(asset.contentType)}",
                            mimeType = asset.contentType,
                        ),
                    ),
                ),
                metadata = buildJsonObject {
                    metadata.forEach(::put)
                    put("boscaMetadataId", reference.metadataId.toString())
                },
            )
            artifacts += artifact
            eventProcessor.sendTaskEvent(
                TaskArtifactUpdateEvent(
                    taskId = context.taskId,
                    contextId = context.contextId,
                    artifact = artifact,
                    append = false,
                    lastChunk = true,
                    metadata = metadata,
                ),
            )
        }
        return artifacts
    }

    private suspend fun SessionEventProcessor.sendFinalTask(
        context: RequestContext<*>,
        state: TaskState,
        text: String,
        metadata: JsonObject? = null,
        artifacts: List<Artifact> = emptyList(),
        history: List<Message> = emptyList(),
    ) {
        val message = Message(
            messageId = Uuid.random().toString(),
            role = Role.Agent,
            parts = listOf(TextPart(text)),
            taskId = context.taskId,
            contextId = context.contextId,
            metadata = metadata,
        )
        sendTaskEvent(
            Task(
                id = context.taskId,
                contextId = context.contextId,
                status = TaskStatus(state = state, message = message, timestamp = Clock.System.now()),
                history = history,
                artifacts = artifacts,
                metadata = metadata,
            ),
        )
    }

    private suspend fun SessionEventProcessor.sendInitialTask(
        context: RequestContext<*>,
        state: TaskState,
        text: String,
    ): Message {
        val message = Message(
            messageId = Uuid.random().toString(),
            role = Role.Agent,
            parts = listOf(TextPart(text)),
            taskId = context.taskId,
            contextId = context.contextId,
        )
        sendTaskEvent(
            Task(
                id = context.taskId,
                contextId = context.contextId,
                status = TaskStatus(state = state, message = message, timestamp = Clock.System.now()),
            ),
        )
        return message
    }

    private fun sessionMetadata(turn: KitChatTurn): JsonObject = buildJsonObject {
        put(KIT_SESSION_METADATA, turn.session.id.toString())
        put("boscaKitStatus", turn.session.status.name)
    }

    private fun defaultTitle(message: String): String =
        message.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80) ?: "A2A conversation"

    private fun extension(contentType: String): String = when (contentType.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/svg+xml" -> "svg"
        else -> "bin"
    }

    companion object {
        const val KIT_SESSION_METADATA = "boscaKitSessionId"
    }
}

internal fun kitAgentCard(url: String, requireBearerToken: Boolean): AgentCard = AgentCard(
    name = "Kit",
    description = "Bosca's AI assistant for analytics, image generation, content work, and platform questions",
    url = url,
    version = Version.current,
    protocolVersion = "0.3.0",
    preferredTransport = TransportProtocol.JSONRPC,
    capabilities = AgentCapabilities(streaming = true, stateTransitionHistory = true),
    defaultInputModes = listOf("text/plain", "text/markdown"),
    defaultOutputModes = listOf(
        "text/plain",
        "text/markdown",
        "application/json",
        "image/jpeg",
        "image/png",
        "image/webp",
    ),
    securitySchemes = if (requireBearerToken) {
        mapOf(
            "bearer" to HTTPAuthSecurityScheme(
                scheme = "bearer",
                bearerFormat = "token",
                description = "Bearer token configured by the Kit A2A server operator",
            ),
        )
    } else {
        emptyMap()
    },
    security = if (requireBearerToken) listOf(mapOf("bearer" to emptyList())) else emptyList(),
    skills = listOf(
        AgentSkill(
            id = "conversation",
            name = "Chat with Kit",
            description = "Ask Kit natural-language questions and continue contextual conversations",
            tags = listOf("conversation", "bosca"),
            examples = listOf("Summarize the content activity from this week"),
        ),
        AgentSkill(
            id = "analytics",
            name = "Analytics",
            description = "Answer analytics questions and return structured visualization data",
            tags = listOf("analytics", "data", "visualization"),
            examples = listOf("Compare daily active users for the last four weeks"),
        ),
        AgentSkill(
            id = "image-generation",
            name = "Image generation",
            description = "Generate images and return them as downloadable A2A file artifacts",
            tags = listOf("image", "generation", "media"),
            examples = listOf("Generate a wide editorial illustration for this article"),
        ),
    ),
)
