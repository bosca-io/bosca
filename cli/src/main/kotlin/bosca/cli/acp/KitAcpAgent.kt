@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package bosca.cli.acp

import bosca.cli.Version
import bosca.cli.api.KitApi
import bosca.cli.api.KitAsset
import bosca.cli.api.KitChatTurn
import bosca.cli.api.KitImageReference
import bosca.cli.api.responseContent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Maps ACP sessions to Kit's durable chat sessions. */
internal class KitAcpAgentSupport(
    private val api: KitApi,
    private val timeoutMillis: Long = KitApi.DEFAULT_TIMEOUT_MILLIS,
) : AgentSupport {

    override suspend fun initialize(clientInfo: ClientInfo) = AgentInfo(
        protocolVersion = LATEST_PROTOCOL_VERSION,
        capabilities = AgentCapabilities(loadSession = true),
        implementation = Implementation(
            name = "bosca-kit",
            version = Version.current,
            title = "Kit",
        ),
    )

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
        val session = api.createSession(sessionTitle(sessionParameters.cwd))
        return KitAcpAgentSession(api, session.id, timeoutMillis)
    }

    override suspend fun loadSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters,
    ): AgentSession {
        val kitSessionId = sessionId.toKitSessionId()
        val session = api.getSession(kitSessionId) ?: error("Kit session not found: $kitSessionId")
        require(session.agentKey == KitApi.KIT_AGENT_KEY) {
            "Session $kitSessionId belongs to agent '${session.agentKey}', not Kit"
        }
        return KitAcpAgentSession(api, kitSessionId, timeoutMillis)
    }

    private fun sessionTitle(cwd: String): String {
        val directory = cwd.trimEnd('/', '\\')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .takeIf(String::isNotBlank)
        return directory?.let { "Kit ACP: $it" } ?: "Kit ACP session"
    }

    private fun SessionId.toKitSessionId(): Uuid =
        runCatching { Uuid.parse(value) }
            .getOrElse { throw IllegalArgumentException("Invalid Kit session ID: $value", it) }
}

/** Executes one ACP prompt at a time against a single Kit chat session. */
internal class KitAcpAgentSession(
    private val api: KitApi,
    private val kitSessionId: Uuid,
    private val timeoutMillis: Long = KitApi.DEFAULT_TIMEOUT_MILLIS,
) : AgentSession {

    override val sessionId = SessionId(kitSessionId.toString())

    private val turnMutex = Mutex()
    private val activeTurn = AtomicReference<Job?>()

    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
        val message = content.filterIsInstance<ContentBlock.Text>()
            .map(ContentBlock.Text::text)
            .filter(String::isNotBlank)
            .joinToString("\n")
            .trim()
        if (message.isBlank()) {
            emit(agentMessage(ContentBlock.Text("Kit requires a non-empty text prompt.")))
            emit(promptResponse(StopReason.REFUSAL))
            return@flow
        }

        turnMutex.withLock {
            val job = currentCoroutineContext().job
            check(activeTurn.compareAndSet(null, job)) { "Kit session $kitSessionId is already processing a prompt" }
            try {
                val turn = api.ask(message = message, sessionId = kitSessionId, timeoutMillis = timeoutMillis)
                emitTurn(turn)
            } finally {
                activeTurn.compareAndSet(job, null)
            }
        }
    }

    override suspend fun cancel() {
        activeTurn.get()?.cancel(CancellationException("Kit ACP prompt cancelled by the client"))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<Event>.emitTurn(turn: KitChatTurn) {
        val response = turn.responseContent()
        val images = response.images.map { reference ->
            val asset = api.downloadAsset(reference.metadataId)
            require(asset.contentType.startsWith("image/")) {
                "Metadata ${reference.metadataId} is ${asset.contentType}, not an image"
            }
            ResolvedImage(reference, asset)
        }
        val responseText = renderResponseText(response.text, images)
        if (responseText.isNotBlank()) {
            emit(agentMessage(ContentBlock.Text(responseText)))
        }
        if (response.displayEvents.isNotEmpty()) {
            emit(agentMessage(
                ContentBlock.Resource(
                    EmbeddedResourceResource.TextResourceContents(
                        text = JsonArray(response.displayEvents).toString(),
                        uri = "bosca://kit/sessions/$kitSessionId/analytics",
                        mimeType = "application/json",
                    ),
                ),
            ))
        }
        for ((reference, asset) in images) {
            emit(agentMessage(
                ContentBlock.Image(
                    data = Base64.getEncoder().encodeToString(asset.bytes),
                    mimeType = asset.contentType,
                    uri = "bosca://content/metadata/${reference.metadataId}",
                    _meta = buildJsonObject {
                        put("boscaMetadataId", reference.metadataId.toString())
                        reference.alt.takeIf(String::isNotBlank)?.let { put("alt", it) }
                    },
                ),
            ))
        }

        val failed = turn.session.status.name == "FAILED" ||
            (response.text.isBlank() && response.displayEvents.isEmpty() && response.images.isEmpty())
        if (response.text.isBlank() && response.displayEvents.isEmpty() && response.images.isEmpty()) {
            emit(agentMessage(ContentBlock.Text(
                if (failed) "Kit failed to complete the request." else "Kit completed the request.",
            )))
        }
        emit(promptResponse(if (failed) StopReason.REFUSAL else StopReason.END_TURN))
    }

    /**
     * Keeps generated images usable in clients that currently display only text agent chunks.
     * The ACP image block remains canonical; its signed URL is also rendered as an absolute image
     * source and an explicit link instead of leaking Studio's relative download route.
     */
    private fun renderResponseText(text: String, images: List<ResolvedImage>): String {
        if (images.isEmpty()) return text
        val rewritten = images.fold(text) { current, image ->
            current.replace(image.reference.url, image.asset.signedDownloadUrl)
        }
        val links = images.joinToString("\n") { image ->
            val label = image.reference.alt.ifBlank { "Generated image" }.markdownLabel()
            "[Open $label](${image.asset.signedDownloadUrl})"
        }
        return listOf(rewritten, links).filter(String::isNotBlank).joinToString("\n\n")
    }

    private fun String.markdownLabel(): String =
        replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")

    private data class ResolvedImage(
        val reference: KitImageReference,
        val asset: KitAsset,
    )

    private fun agentMessage(content: ContentBlock) =
        Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(content))

    private fun promptResponse(stopReason: StopReason) =
        Event.PromptResponseEvent(PromptResponse(stopReason))
}
