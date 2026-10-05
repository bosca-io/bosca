package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.ChatMessageInput
import bosca.graphql.gen.ChatMessagePartInput
import bosca.graphql.gen.ChatSessionInput
import bosca.graphql.gen.ChatSessionStatus
import bosca.graphql.gen.CreateKitChatSession
import bosca.graphql.gen.DeleteKitChatSession
import bosca.graphql.gen.GetKitChatSession
import bosca.graphql.gen.GetMetadataDownload
import bosca.graphql.gen.IKitChatSessionFragment
import bosca.graphql.gen.IKitChatSessionSummaryFragment
import bosca.graphql.gen.ListKitChatSessions
import bosca.graphql.gen.SendKitChatMessage
import java.time.ZonedDateTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

data class KitChatMessage(
    val id: Uuid,
    val sessionId: Uuid,
    val author: String,
    val created: ZonedDateTime,
    val event: JsonElement?,
)

data class KitChatSession(
    val id: Uuid,
    val agentKey: String,
    val title: String,
    val processing: Boolean,
    val status: ChatSessionStatus,
    val created: ZonedDateTime,
    val modified: ZonedDateTime,
    val messages: List<KitChatMessage>,
)

data class KitChatTurn(
    val session: KitChatSession,
    val messages: List<KitChatMessage>,
)

data class KitAsset(
    val metadataId: Uuid,
    val contentType: String,
    val contentLength: Long?,
    val bytes: ByteArray,
    val signedDownloadUrl: String,
)

/** Typed client for Kit conversations and the content assets Kit produces. */
class KitApi(
    network: NetworkClient,
    private val pollIntervalMillis: Long = 500,
) : Api(network) {

    suspend fun listSessions(): List<KitChatSession> =
        network.boscaGraphql.execute(ListKitChatSessions, Unit).chatSessions.all
            .asSequence()
            .filter { it.agentKey == KIT_AGENT_KEY }
            .map { it.toSession() }
            .toList()

    suspend fun getSession(id: Uuid): KitChatSession? =
        network.boscaGraphql.execute(GetKitChatSession, GetKitChatSession.Variables(id))
            .chatSessions.session?.toSession()

    suspend fun createSession(title: String): KitChatSession =
        network.boscaGraphql.execute(
            CreateKitChatSession,
            CreateKitChatSession.Variables(ChatSessionInput(agentKey = KIT_AGENT_KEY, title = title)),
        ).chatSessions.create.toSession()

    suspend fun deleteSession(id: Uuid): Boolean =
        network.boscaGraphql.execute(DeleteKitChatSession, DeleteKitChatSession.Variables(id))
            .chatSessions.delete

    /** Sends one turn and waits until Kit publishes its final assistant response or stops processing. */
    suspend fun ask(
        message: String,
        sessionId: Uuid? = null,
        title: String? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): KitChatTurn {
        require(message.isNotBlank()) { "Kit message must not be blank" }
        require(timeoutMillis > 0) { "Kit timeout must be positive" }

        val initial = sessionId?.let { id ->
            getSession(id) ?: error("Kit session not found: $id")
        } ?: createSession(title?.takeIf(String::isNotBlank) ?: defaultTitle(message))
        require(initial.agentKey == KIT_AGENT_KEY) { "Session ${initial.id} belongs to agent '${initial.agentKey}', not Kit" }
        check(!initial.processing) { "Kit session ${initial.id} is already processing another message" }

        val accepted = network.boscaGraphql.execute(
            SendKitChatMessage,
            SendKitChatMessage.Variables(
                sessionId = initial.id,
                message = ChatMessageInput(
                    role = "user",
                    parts = listOf(ChatMessagePartInput(type = "text", text = message)),
                ),
            ),
        ).chatSessions.send
        check(accepted == true) { "Kit did not accept the message" }

        val existingIds = initial.messages.mapTo(HashSet()) { it.id }
        val completed = withTimeoutOrNull(timeoutMillis) {
            var current = getSession(initial.id) ?: error("Kit session disappeared: ${initial.id}")
            while (current.processing && !current.hasNewAssistantMessage(existingIds)) {
                delay(pollIntervalMillis)
                current = getSession(initial.id) ?: error("Kit session disappeared: ${initial.id}")
            }
            current
        } ?: error("Timed out waiting for Kit after ${timeoutMillis / 1_000} seconds")

        return KitChatTurn(completed, completed.messages.filterNot { it.id in existingIds })
    }

    /** Resolves Bosca's signed download URL and returns the stored asset bytes. */
    suspend fun downloadAsset(metadataId: Uuid): KitAsset {
        val content = network.boscaGraphql.execute(
            GetMetadataDownload,
            GetMetadataDownload.Variables(metadataId),
        ).content.metadata?.content ?: error("Content not found for metadata $metadataId")
        val download = content.urls?.download ?: error("Content download is unavailable for metadata $metadataId")
        val bytes = Files(network).downloadBytes(download.url, download.headers)
        return KitAsset(metadataId, content.type, content.length, bytes, download.url)
    }

    private fun defaultTitle(message: String): String =
        message.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80) ?: "Kit conversation"

    private fun KitChatSession.hasNewAssistantMessage(existingIds: Set<Uuid>): Boolean =
        messages.any { it.id !in existingIds && it.isAssistantMessage() }

    private fun IKitChatSessionSummaryFragment.toSession() = KitChatSession(
        id = id,
        agentKey = agentKey,
        title = title,
        processing = processing,
        status = status,
        created = created,
        modified = modified,
        messages = emptyList(),
    )

    private fun IKitChatSessionFragment.toSession() = KitChatSession(
        id = id,
        agentKey = agentKey,
        title = title,
        processing = processing,
        status = status,
        created = created,
        modified = modified,
        messages = messages.map {
            KitChatMessage(
                id = it.id,
                sessionId = it.sessionId,
                author = it.author,
                created = it.created,
                event = it.event,
            )
        },
    )

    companion object {
        const val KIT_AGENT_KEY = "kit"
        const val DEFAULT_TIMEOUT_MILLIS = 5 * 60 * 1_000L
    }
}
