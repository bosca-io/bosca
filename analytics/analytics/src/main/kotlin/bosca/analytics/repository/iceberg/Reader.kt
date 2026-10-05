package bosca.analytics.repository.iceberg

import bosca.analytics.model.Content
import bosca.analytics.model.Device
import bosca.analytics.model.Element
import bosca.analytics.model.Event
import bosca.analytics.model.EventContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.model.Page
import bosca.serialization.LocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.serialization.json.Json
import org.apache.iceberg.data.Record
import org.apache.iceberg.io.CloseableIterable
import java.time.ZoneOffset

class Reader(reader: CloseableIterable<Record>) : Iterator<Events> {
    private data class Envelope(
        val context: EventContext,
        val sent: LocalDateTime,
        val sentMicros: Long,
        val received: LocalDateTime,
    )

    private val itr = reader.iterator()
    private var envelope: Envelope? = null
    private var events: MutableList<Event> = mutableListOf()

    override fun hasNext() = itr.hasNext() || envelope != null

    override fun next(): Events {
        while (itr.hasNext()) {
            read(itr.next())?.let { return it }
        }
        val completedEnvelope = envelope ?: throw NoSuchElementException()
        envelope = null
        return newEvents(completedEnvelope).also { events.clear() }
    }

    private fun newEvents(envelope: Envelope) = Events(
        context = envelope.context,
        events = events.toList(),
        sent = envelope.sent.toInstant(ZoneOffset.UTC).toEpochMilli(),
        sentMicros = envelope.sentMicros,
        received = envelope.received.toInstant(ZoneOffset.UTC).toEpochMilli(),
    )

    private fun read(record: Record): Events? {
        val contextRecord = record.getField("context") as Record
        val deviceRecord = contextRecord.getField("device") as Record

        val context = EventContext(
            appId = contextRecord.getField("app_id") as String,
            appVersion = contextRecord.getField("app_version") as String,
            sessionId = contextRecord.getField("session_id") as String,
            userId = contextRecord.getField("user_id") as String?,
            device = Device(
                installationId = deviceRecord.getField("installation_id") as String,
                manufacturer = deviceRecord.getField("manufacturer") as String,
                model = deviceRecord.getField("model") as String,
                platform = deviceRecord.getField("platform") as String,
                primaryLocale = deviceRecord.getField("primary_locale") as String,
                systemName = deviceRecord.getField("system_name") as String,
                timezone = deviceRecord.getField("timezone") as String,
                type = deviceRecord.getField("type") as String,
                version = deviceRecord.getField("version") as String
            )
        )

        val nextEnvelope = Envelope(
            context = context,
            sent = record.getField("sent") as LocalDateTime,
            sentMicros = record.getField("sent_micros") as Long,
            received = record.getField("received") as LocalDateTime,
        )
        val completed = envelope?.takeIf { current ->
            current.context != nextEnvelope.context ||
                current.sent.toKotlinLocalDateTime() != nextEnvelope.sent.toKotlinLocalDateTime() ||
                current.sentMicros != nextEnvelope.sentMicros ||
                current.received.toKotlinLocalDateTime() != nextEnvelope.received.toKotlinLocalDateTime()
        }?.let(::newEvents)
        if (completed != null) {
            events.clear()
        }
        envelope = nextEnvelope

        val elementRecord = record.getField("element") as Record?

        // page was added in Phase 3 of the conversion-goals overhaul. Records
        // written before that schema evolution have no field with this name,
        // so the cast must allow null and the resulting Page must be left null
        // rather than constructed with all-null members (which would be a
        // false positive on legacy rows).
        val pageRecord = record.getField("page") as Record?
        val page = pageRecord?.let {
            Page(
                path = it.getField("path") as String?,
                url = it.getField("url") as String?,
                title = it.getField("title") as String?,
            )
        }

        events.add(
            Event(
                created = (record.getField("created") as LocalDateTime).toInstant(ZoneOffset.UTC).toEpochMilli(),
                createdMicros = record.getField("created_micros") as Long?,
                type = EventType.valueOf(record.getField("type") as String),
                clientId = record.getField("client_id") as String,
                element = elementRecord?.let {
                    @Suppress("UNCHECKED_CAST")
                    val contentRecords = it.getField("content") as List<Record>
                    Element(
                        id = it.getField("id") as String,
                        type = it.getField("type") as String,
                        content = contentRecords.map { contentRecord ->
                            Content(
                                id = contentRecord.getField("id") as String,
                                type = contentRecord.getField("type") as String,
                                index = contentRecord.getField("index") as Long?,
                                percent = contentRecord.getField("percent") as Double?
                            )
                        },
                        extras = Json.parseToJsonElement(it.getField("extras") as String)
                    )
                },
                page = page,
            )
        )
        return completed
    }
}
