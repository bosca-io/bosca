package bosca.analytics.transform.iceberg

import bosca.analytics.iceberg.Browser
import bosca.analytics.iceberg.Content
import bosca.analytics.iceberg.Context
import bosca.analytics.iceberg.Device
import bosca.analytics.iceberg.Element
import bosca.analytics.iceberg.Error
import bosca.analytics.iceberg.Geo
import bosca.analytics.iceberg.Page
import bosca.analytics.model.Browser
import bosca.analytics.model.Content
import bosca.analytics.model.Device
import bosca.analytics.model.Element
import bosca.analytics.model.ErrorInfo
import bosca.analytics.model.icebergEventTypeColumnValue
import bosca.analytics.model.Event
import bosca.analytics.model.EventContext
import bosca.analytics.model.Events
import bosca.analytics.model.Geo
import bosca.analytics.model.Page
import bosca.analytics.transform.EventsTransform
import bosca.serialization.UUID
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import org.apache.iceberg.Schema
import org.apache.iceberg.data.GenericRecord
import org.apache.iceberg.data.Record
import kotlin.time.Instant
import kotlin.uuid.toJavaUuid

class IcebergEventsToRecordTransform(private val schema: Schema) : EventsTransform<List<Record>> {

    override suspend fun transform(item: Events): List<Record> {
        return item.events.map { it.toRecord(item) }
    }

    private fun Long.toTimestamp() = Instant
        .fromEpochMilliseconds(this)
        .toLocalDateTime(TimeZone.UTC)
        .toJavaLocalDateTime()

    private fun Event.toRecord(events: Events): Record {
        val event: Record = GenericRecord.create(schema)
        event.setField("id", UUID.random().toJavaUuid())
        event.setField("client_id", clientId)
        event.setField("type", icebergEventTypeColumnValue(type))
        event.setField("sent", events.sent.toTimestamp())
        event.setField("sent_micros", events.sentMicros)
        event.setField("received", (events.received ?: 0L).toTimestamp())
        event.setField("received_micros", events.receivedMicros)
        event.setField("created", created.toTimestamp())
        event.setField("created_micros", createdMicros)
        event.setField("context", events.context?.toRecord())
        event.setField("element", element?.toRecord())
        event.setField("error", error?.toRecord())
        event.setField("page", page?.toRecord())
        return event
    }

    private fun Page.toRecord(): Record {
        val record = GenericRecord.create(Page)
        record.setField("path", path)
        record.setField("url", url)
        record.setField("title", title)
        return record
    }

    private fun EventContext.toRecord(): Record {
        val record = GenericRecord.create(Context)
        record.setField("app_id", appId)
        record.setField("app_version", appVersion)
        record.setField("browser", browser?.toRecord())
        record.setField("device", device.toRecord())
        record.setField("geo", geo?.toRecord())
        record.setField("session_id", sessionId)
        record.setField("user_id", userId)
        return record
    }

    private fun Element.toRecord(): Record {
        val element = GenericRecord.create(Element)
        element.setField("id", id ?: "null")
        element.setField("type", type ?: "null")
        element.setField("content", content?.map { it.toRecord() } ?: emptyList<Content>())
        element.setField("extras", extras?.toString() ?: "null")
        return element
    }

    private fun Content.toRecord(): Record {
        val content = GenericRecord.create(Content)
        content.setField("id", id)
        content.setField("type", type)
        content.setField("index", index)
        content.setField("percent", percent)
        return content
    }

    private fun Geo.toRecord(): Record {
        val geo = GenericRecord.create(Geo)
        geo.setField("city", city)
        geo.setField("country", country)
        geo.setField("continent", continent)
        geo.setField("longitude", longitude)
        geo.setField("latitude", latitude)
        geo.setField("region", region)
        geo.setField("region_code", regionCode)
        geo.setField("postal_code", postalCode)
        geo.setField("timezone", timezone)
        return geo
    }

    private fun Browser.toRecord(): Record {
        val browser = GenericRecord.create(Browser)
        browser.setField("agent", agent)
        return browser
    }

    private fun Device.toRecord(): Record {
        val device = GenericRecord.create(Device)
        device.setField("installation_id", installationId)
        device.setField("manufacturer", manufacturer)
        device.setField("model", model)
        device.setField("platform", platform)
        device.setField("primary_locale", primaryLocale)
        device.setField("system_name", systemName)
        device.setField("timezone", timezone)
        device.setField("type", type)
        device.setField("version", version)
        return device
    }

    private fun ErrorInfo.toRecord(): Record {
        val error = GenericRecord.create(Error)
        error.setField("message", message)
        error.setField("type", type)
        error.setField("stack_trace", stackTrace)
        error.setField("fatal", fatal)
        error.setField("code", code)
        error.setField("fingerprint", fingerprint)
        error.setField("context_json", contextJson)
        return error
    }
}