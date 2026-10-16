package bosca.analytics.transform.fums

import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.transform.EventPipelineTransform
import bosca.analytics.jobs.FumsDeliveryJob
import bosca.analytics.jobs.prepare
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/** Queues client-authored Bible usage for delivery by the FUMS worker. */
class FumsAnalyticsTransform(private val queue: JobQueue) : EventPipelineTransform {
    override suspend fun transform(context: EventPipelineContext, events: Events): Events {
        val usage = events.context ?: return events
        val device = usage.device.installationId.trim().takeIf { it.isNotEmpty() } ?: return events
        val session = usage.sessionId.trim().takeIf { it.isNotEmpty() } ?: return events
        val deviceId = hash(device)
        val user = usage.userId?.let { it.trim().takeIf(String::isNotEmpty)?.let(::hash) }
        events.events.forEach { event ->
            val element = event.element ?: return@forEach
            val audio = event.type == EventType.Interaction && element.type == "bible-audio"
            if (!audio && !(event.type == EventType.Impression && element.type == "bible-text")) return@forEach
            val extras = element.extras as? JsonObject ?: return@forEach
            val bibleId = extras.uuid("bible_id") ?: return@forEach
            val version = (extras["bible_version"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return@forEach
            val variant = extras.string("bible_variant") ?: return@forEach
            val usfm = extras.string("bible_usfm")?.takeIf { chapter.matches(it) } ?: return@forEach
            val audioId = if (audio) extras.uuid("bible_audio_id") ?: return@forEach else null
            val job = FumsDeliveryJob(bibleId, version, variant, usfm, audioId, deviceId, session, user).prepare()
            val jobId = queue.enqueue(job)
            log.info(
                "FUMS usage submitted: jobId={} bibleId={} chapter={} kind={}",
                jobId, bibleId, usfm, if (audio) "audio" else "text",
            )
        }
        return events
    }

    private fun JsonObject.string(key: String): String? {
        val value = get(key) as? JsonPrimitive ?: return null
        if (!value.isString) return null
        return value.content.trim().takeIf(String::isNotEmpty)
    }

    private fun JsonObject.uuid(key: String): UUID? = string(key)?.let {
        try { UUID.parse(it) } catch (_: IllegalArgumentException) { null }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        val log = LoggerFactory.getLogger(FumsAnalyticsTransform::class.java)
        val chapter = Regex("[A-Z0-9]+\\.([0-9]+|intro)")
    }
}
