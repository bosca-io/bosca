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
import bosca.cli.api.responseContent
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
import kotlinx.serialization.json.put

/** Bridges A2A tasks to Kit's durable, server-side chat sessions. */
internal class KitA2AAgentExecutor(
    private val api: KitApi,
    private val timeoutMillis: Long = KitApi.DEFAULT_TIMEOUT_MILLIS,
) : AgentExecutor {

    private val sessions = ConcurrentHashMap<String, Uuid>()
    private val contextLocks = ConcurrentHashMap<String, Mutex>()
    private val sessionLocks = ConcurrentHashMap<Uuid, Mutex>()

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

        val workingMessage = eventProcessor.sendInitialTask(
            context,
            TaskState.Working,
            "Kit is working on the request",
        )
        try {
            val turn = contextLocks.computeIfAbsent(context.contextId) { Mutex() }.withLock {
                ask(context, userText)
            }
            val response = turn.responseContent()
            val metadata = sessionMetadata(turn)
            val artifacts = sendArtifacts(context, eventProcessor, turn, response, metadata)

            val failed = turn.session.status.name == "FAILED" ||
                (response.text.isBlank() && response.displayEvents.isEmpty() && response.images.isEmpty())
            val text = response.text.ifBlank {
                if (failed) "Kit failed to complete the request." else "Kit completed the request."
            }
            eventProcessor.sendFinalTask(
                context = context,
                state = if (failed) TaskState.Failed else TaskState.Completed,
                text = text,
                metadata = metadata,
                artifacts = artifacts,
                history = listOf(workingMessage),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            eventProcessor.sendFinalTask(
                context,
                TaskState.Failed,
                "Kit request failed: ${e.message ?: "unknown error"}",
                history = listOf(workingMessage),
            )
        }
    }

    private suspend fun ask(context: RequestContext<MessageSendParams>, message: String): KitChatTurn {
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
