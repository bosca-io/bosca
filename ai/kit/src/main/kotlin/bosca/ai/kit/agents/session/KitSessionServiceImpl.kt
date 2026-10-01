package bosca.ai.kit.agents.session

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.serialization.JSONElement
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONElement
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import ai.koog.serialization.KSerializerTypeToken
import ai.koog.serialization.annotations.InternalKoogSerializationApi
import bosca.ai.chat.model.ChatHistoryMessage
import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.model.ChatSessionInput
import bosca.ai.chat.model.ToolDisplayEvent
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitSerializer
import bosca.ai.kit.configuration.KitJson
import bosca.ai.kit.tools.KitToolContext
import bosca.analytics.model.AnalyticsVisualizationType
import bosca.db.transaction
import bosca.serialization.JsonConverter.asJsonElement
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.ZoneOffset
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

/**
 * Durable [KitSessionService] — the `@ServiceImplementation` DI wires up. It splits a checkpoint across
 * the two stores where each half belongs:
 *
 *  - The (potentially large) [AgentCheckpointData] bytes go to **object storage** under
 *    `kit/sessions/<runId>/<checkpointId>`, keyed by the checkpoint's *real* id.
 *  - A small **index row** goes to Postgres ([KitSessionRepository] → `kit.checkpoint`), so a run's
 *    checkpoints can be LISTED and the latest found without scanning storage.
 *
 * A `runId` is the run a checkpoint belongs to: the parent planner run — whose id IS the chat session
 * id — or a sub-agent run (a per-action UUID). The index row also records the owning `session_id` (from
 * the ambient [KitSessionContext], or the runId itself for the parent), so a session's parent and
 * sub-agent runs are grouped and removable together. Serialization uses the platform [json] so nested
 * (and `@Contextual`) types resolve through Bosca's serializers.
 */
@ServiceImplementation
@OptIn(InternalKoogSerializationApi::class)
class KitSessionServiceImpl(
    private val chatHistory: ChatHistoryService,
    private val repository: KitSessionRepository,
    private val objectStorage: ObjectStorageService,
    private val json: KitJson,
) : KitSessionService {

    private val serializer = KitSerializer(KotlinxSerializer(json.json))

    override suspend fun getMessages(sessionId: UUID): List<Message> =
        chatHistory.getMessages(sessionId)
            // Chat history mixes two lanes: conversational rows (a Koog Message, authored by a Role) and
            // display rows (a ToolDisplayEvent visualization, authored by AUTHOR — for the UI only). Only
            // the conversational lane is a decodable Message, so ChatMemory replays just those.
            .filter { it.author in MESSAGE_AUTHORS }
            .map {
                val message = serializer.decodeFromJSONElement<Message>(it.event.toKoogJSONElement(), KSerializerTypeToken(Message.serializer()))
                if (message.id == null) {
                    when (message) {
                        is Message.System -> message.copy(id = it.id.toString())
                        is Message.User -> message.copy(id = it.id.toString())
                        is Message.Assistant -> message.copy(id = it.id.toString())
                    }
                } else {
                    message
                }
            }

    override suspend fun setMessages(sessionId: UUID, messages: List<Message>) {
        if (chatHistory.getSession(sessionId) == null) {
            val parentSessionId = currentCoroutineContext()[KitSessionContext]?.parentSessionId ?: error("No KitSessionContext")
            val principalId = currentCoroutineContext()[KitToolContext]?.authentication?.principal()?.id ?: error("No authentication context")
            chatHistory.createSession(
                principalId, ChatSessionInput(
                    agentKey = "kit",
                    title = "Session",
                    parentSessionId = parentSessionId,
                ), sessionId
            )
        }

        chatHistory.setMessages(sessionId, messages.map {
            ChatHistoryMessage(
                id = it.id?.let { UUID.parse(it) } ?: Uuid.random(),
                sessionId = sessionId,
                author = it.role.name,
                content = null,
                created = OffsetDateTime.ofInstant(it.metaInfo.timestamp.toJavaInstant(), ZoneOffset.UTC),
                event = serializer.encodeToJSONElement(it, KSerializerTypeToken(Message.serializer())).toKotlinxJsonElement()
            )
        })
    }

    override suspend fun saveCheckpoint(sessionId: UUID, checkpoint: AgentCheckpointData) {
        val bytes = serializer.encodeToJSONElement(checkpoint, KSerializerTypeToken(AgentCheckpointData.serializer()))
            .toKotlinxJsonElement()
            .toString()
            .encodeToByteArray()
        objectStorage.setInputStream(checkpointPath(sessionId, UUID.parse(checkpoint.checkpointId), checkpoint.version), bytes.inputStream(), bytes.size.toLong())
        // The owning chat session is the ambient parent (set by an action around a sub-agent run); the
        // parent planner run has none, and its own id already IS the chat session.
        val parentSessionId = currentCoroutineContext()[KitSessionContext]?.parentSessionId
        repository.add(
            KitSessionCheckpoint(
                parentSessionId = parentSessionId,
                sessionId = sessionId,
                checkpointId = UUID.parse(checkpoint.checkpointId),
                version = checkpoint.version,
            ),
        )
    }

    override suspend fun getCheckpoints(sessionId: UUID): List<AgentCheckpointData> =
        repository.bySession(sessionId).map { load(it.sessionId, it.checkpointId, it.version) }

    override suspend fun getLatestCheckpoint(sessionId: UUID): AgentCheckpointData? =
        repository.latestBySession(sessionId)?.let { load(it.sessionId, it.checkpointId, it.version) }

    override suspend fun clearSession(parentSessionId: UUID) {
        // Drop the blobs (keyed by each run's own session id) first, then the index rows that point at them.
        repository.byParentSession(parentSessionId).forEach { objectStorage.delete(checkpointPath(it.sessionId, it.checkpointId, it.version)) }
        repository.deleteByParentSession(parentSessionId)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun load(sessionId: UUID, checkpointId: UUID, version: Long): AgentCheckpointData {
        val data = objectStorage.getInputStream(checkpointPath(sessionId, checkpointId, version)).use {
            val data = it.readAllBytes().decodeToString()
            val element = json.json.parseToJsonElement(data).toKoogJSONElement()
            serializer.decodeFromJSONElement<AgentCheckpointData>(element, KSerializerTypeToken(AgentCheckpointData.serializer()))
        }
        return data
    }

    override suspend fun recordUserMessage(sessionId: UUID, message: ChatMessageInput) {
        // Persist the user's turn as a real Koog Message (serialized with the kit Json), so chat history
        // and ChatMemory share ONE shape and the turn survives a refresh.
        val text = message.parts.joinToString("\n") { it.text }
        chatHistory.addMessage(historyMessage(sessionId, Message.User(text, RequestMetaInfo.Empty)))
    }

    override suspend fun recordResponse(sessionId: UUID, response: KitResponse) {
        // The conversational lane: Kit's reply as a real Koog Message, so it round-trips back through
        // getMessages() into ChatMemory and renders as a text bubble.
        chatHistory.addMessage(historyMessage(sessionId, Message.Assistant(response.displayText(), ResponseMetaInfo.Empty)))
        // The display lane: an analytics turn ALSO emits a chart/table the UI renders inline. A
        // ToolDisplayEvent is NOT a Koog Message, so it can't share the conversational lane — it rides
        // its own row (author = AUTHOR) which getMessages() skips. We keep the visualization for the
        // frontend without ever trying to decode it as conversation.
        if (response is KitResponse.Analytics) {
            chatHistory.addMessage(historyMessage(sessionId, AUTHOR, visualizationEvent(response)))
        }
        // The display lane also carries a GraphQL turn's RICH result — the returned data plus what it is
        // (its type + SDL) — so the UI can render it beyond the text summary. Not a Koog Message, so it
        // rides its own row (author = AUTHOR) which getMessages() skips.
        if (response is KitResponse.GraphQL) {
            chatHistory.addMessage(historyMessage(sessionId, AUTHOR, graphQLResultEvent(response)))
        }
    }

    /** A conversational-lane row carrying a Koog [message], serialized with the kit Json. */
    private fun historyMessage(sessionId: Uuid, message: Message) = ChatHistoryMessage(
        id = Uuid.random(),
        sessionId = sessionId,
        author = message.role.name,
        event = serializer.encodeToJSONElement(message, KSerializerTypeToken(Message.serializer())).toKotlinxJsonElement(),
    )

    /** A display-lane row carrying a non-conversational [event] (e.g. a visualization), tagged by [author]. */
    private fun historyMessage(sessionId: Uuid, author: String, event: JsonObject) = ChatHistoryMessage(
        id = Uuid.random(),
        sessionId = sessionId,
        author = author,
        event = event,
    )

    private fun checkpointPath(sessionId: UUID, checkpointId: UUID, version: Long): ObjectPath =
        StringObjectPath("kit/sessions/$sessionId/$checkpointId/$version.json")

    /** The user-facing text Kit shows for each response shape (the rich payload is carried elsewhere). */
    private fun KitResponse.displayText(): String = when (this) {
        is KitResponse.Text -> text
        is KitResponse.Question -> text
        is KitResponse.Document -> message
        is KitResponse.Analytics -> summary
        is KitResponse.Description -> description
        is KitResponse.Topics -> if (topics.isEmpty()) "I didn't find any matching topics." else "Suggested topics: " + topics.joinToString(", ") { it.name }
        is KitResponse.ReadingTime -> "About $readingTimeInMinutes min read ($totalWordCount words)."
        is KitResponse.GraphQL -> message
    }

    /**
     * The `event_data` for a visualization turn — a [ToolDisplayEvent] (`type = "tool-display"`) carrying
     * the analytics result as `data` rows under a [bosca.analytics.model.AnalyticsVisualizationType]-named
     * chart type, which the Studio chat renders inline. Reuses the platform's existing visualization
     * vocabulary rather than inventing a Kit-specific one.
     */
    private fun visualizationEvent(analytics: KitResponse.Analytics): JsonObject {
        val type = visualizationType(analytics.visualization)
        val data = analytics.rows.map { row ->
            buildJsonObject {
                analytics.columns.forEachIndexed { i, column ->
                    // Keep numbers numeric so charts can plot them; non-numeric cells stay strings.
                    val cell = row.getOrElse(i) { "" }
                    val number: Number? = cell.toLongOrNull() ?: cell.toDoubleOrNull()
                    if (number != null) put(column, number) else put(column, cell)
                }
            }
        }
        val event = ToolDisplayEvent(
            requestId = Uuid.random().toString(),
            title = analytics.summary,
            visualizationType = type,
            configuration = visualizationConfig(type, analytics.columns),
            data = data,
            // The executed SQL rides along so the UI can show the user where the numbers came from.
            sourceQuery = analytics.query.takeIf { it.isNotBlank() },
            savedQueryId = analytics.savedQueryId,
            savedQueryKey = analytics.savedQueryKey,
            visualizationId = analytics.visualizationId,
            dashboardId = analytics.dashboardId,
            investigation = analytics.investigation,
        )
        // encodeDefaults is off on the platform Json, so the defaulted `type` discriminator would be
        // dropped; add it back explicitly so the chat client can identify the visualization event.
        val encoded = serializer.encodeToJSONElement(event, KSerializerTypeToken(ToolDisplayEvent.serializer())).toKotlinxJsonElement().jsonObject
        return JsonObject(encoded + ("type" to JsonPrimitive(event.type)))
    }

    /**
     * The display-lane `event_data` for a GraphQL turn: the rich [KitResponse.GraphQL] result — the
     * returned `data` JSON, the GraphQL `dataType` it is, and that type's `sdl` — under a
     * `graphql-result` type the chat client can render knowing the shape. The text summary rides the
     * conversational lane separately.
     */
    private fun graphQLResultEvent(response: KitResponse.GraphQL): JsonObject = buildJsonObject {
        put("type", "graphql-result")
        put("dataType", response.type)
        put("sdl", response.sdl)
        put("data", response.data)
    }

    /** Normalizes the SQL agent's suggested visualization to an `AnalyticsVisualizationType` name; tabular by default. */
    private fun visualizationType(suggested: String): AnalyticsVisualizationType =
        AnalyticsVisualizationType.entries.firstOrNull { it.name.equals(suggested, ignoreCase = true) } ?: AnalyticsVisualizationType.TABLE

    /** Best-effort field mapping (first column = x/label, the rest = y/value) the client charts from. */
    private fun visualizationConfig(type: AnalyticsVisualizationType, columns: List<String>): JsonObject = buildJsonObject {
        when (type) {
            AnalyticsVisualizationType.BAR, AnalyticsVisualizationType.LINE, AnalyticsVisualizationType.SCATTER, AnalyticsVisualizationType.BUBBLE, AnalyticsVisualizationType.STACKED_AREA -> {
                columns.firstOrNull()?.let { put("x", it) }
                if (columns.size > 1) putJsonArray("y") { columns.drop(1).forEach { add(it) } }
            }

            AnalyticsVisualizationType.PIE, AnalyticsVisualizationType.DOUGHNUT -> {
                columns.firstOrNull()?.let { put("label", it) }
                columns.getOrNull(1)?.let { put("value", it) }
            }

            AnalyticsVisualizationType.NUMBER -> columns.firstOrNull()?.let { put("value", it) }
            // TABLE / others: rendered straight from columns + data, no axis mapping needed.
            else -> {}
        }
    }

    override suspend fun getStorage(sessionId: UUID): Map<String, JSONElement> {
        return chatHistory.getSession(sessionId)?.state?.jsonObject?.mapValues { it.value.toKoogJSONElement() } ?: emptyMap()
    }

    override suspend fun setStorage(sessionId: UUID, storage: Map<String, JSONElement>) = transaction<Unit> {
        val state = JsonObject(storage.mapValues { it.value.toKotlinxJsonElement() })
        chatHistory.updateSessionState(sessionId, state)
    }

    private companion object {
        /** Chat-history author for display-lane rows (visualizations) — NOT a Koog [Message.Role]. */
        const val AUTHOR = "kit"

        /** Authors that mark a row as a conversational Koog [Message]; everything [getMessages] decodes. */
        val MESSAGE_AUTHORS: Set<String> = Message.Role.entries.mapTo(mutableSetOf()) { it.name }
    }
}
