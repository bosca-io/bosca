package bosca.analytics.persistence.room

import bosca.analytics.api.AnalyticsContext
import bosca.analytics.api.AnalyticsEvent
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.api.Browser
import bosca.analytics.persistence.AnalyticsEventStore
import bosca.analytics.persistence.ContextEvents
import bosca.analytics.persistence.StoredAnalyticsEvent
import kotlinx.serialization.json.Json

internal class RoomAnalyticsEventStore(
    database: AnalyticsDatabase,
    private val namespace: String,
) : AnalyticsEventStore {
    private val storage = database.storage()

    override suspend fun add(event: StoredAnalyticsEvent) {
        storage.add(event.toContextEntity(), event.toEventEntity())
    }

    override suspend fun read(limit: Int): List<ContextEvents> {
        require(limit > 0) { "Event read limit must be positive" }
        return storage.readContextEvents(namespace, limit).map { relation ->
            ContextEvents(
                contextId = relation.context.id,
                context = relation.context.toContext(),
                events = relation.events.map { it.toEvent() },
            )
        }
    }

    override suspend fun remove(clientIds: Set<String>) {
        storage.remove(namespace, clientIds)
    }

    override suspend fun size(): Int = storage.countEvents(namespace)

    private fun StoredAnalyticsEvent.toContextEntity() = AnalyticsContextEntity(
        id = contextId,
        appId = context.appId,
        appVersion = context.appVersion,
        clientId = context.clientId,
        device = context.device,
        geo = context.geo,
        browserAgent = context.browser?.agent,
        sessionId = context.sessionId,
        userId = context.userId,
    )

    private fun StoredAnalyticsEvent.toEventEntity() = AnalyticsEventEntity(
        clientId = event.clientId,
        namespace = namespace,
        contextId = contextId,
        type = event.type.wireName,
        created = event.created,
        createdMicros = event.createdMicros,
        elementPayload = JSON.encodeToString(bosca.analytics.api.AnalyticsElement.serializer(), event.element),
        pagePayload = event.page?.let { JSON.encodeToString(bosca.analytics.api.Page.serializer(), it) },
        errorPayload = event.error?.let { JSON.encodeToString(bosca.analytics.api.ErrorInfo.serializer(), it) },
    )

    private fun AnalyticsContextEntity.toContext() = AnalyticsContext(
        appId = appId,
        appVersion = appVersion,
        clientId = clientId,
        device = device,
        geo = geo,
        browser = browserAgent?.let(::Browser),
        sessionId = sessionId,
        userId = userId,
    )

    private fun AnalyticsEventEntity.toEvent() = AnalyticsEvent(
        clientId = clientId,
        type = AnalyticsEventType.entries.first { it.wireName == type },
        created = created,
        createdMicros = createdMicros,
        element = JSON.decodeFromString(bosca.analytics.api.AnalyticsElement.serializer(), elementPayload),
        page = pagePayload?.let { JSON.decodeFromString(bosca.analytics.api.Page.serializer(), it) },
        error = errorPayload?.let { JSON.decodeFromString(bosca.analytics.api.ErrorInfo.serializer(), it) },
    )

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
