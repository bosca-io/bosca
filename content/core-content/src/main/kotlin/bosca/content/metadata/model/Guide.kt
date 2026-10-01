package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import net.fortuna.ical4j.model.Recur
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.TimeZone

@BatchKey(type = MetadataCacheKeyId::class)
@Serializable
class Guide(
    @ColumnName("metadata_id")
    @Contextual
    override val metadataId: UUID,
    override val version: Int,
    val rrule: String?,
    val type: GuideType,
    @Contextual
    @ColumnName("template_metadata_id")
    val templateMetadataId: UUID?,
    @ColumnName("template_metadata_version")
    val templateMetadataVersion: Int?
) : MetadataCacheKeyable {

    override val key: String? = null
    override val step: Long? = null

    private fun String.parse(dateFormat: DateFormat): ZonedDateTime {
        val utc = ZoneId.of("UTC")
        val time = dateFormat.parse(this)
        val instant = Instant.ofEpochMilli(time.time)
        return ZonedDateTime.ofInstant(instant, utc)
    }

    fun getRecurrenceDates(stepCount: Int): List<OffsetDateTime> {
        val parts = rrule?.split("\n") ?: return emptyList()
        val dateTimePattern = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        dateTimePattern.timeZone = TimeZone.getTimeZone("UTC")
        val start = parts.find { it.startsWith("DTSTART") }
            ?.substringAfter(":")
            ?.parse(dateTimePattern)
            ?.toOffsetDateTime()
            ?: OffsetDateTime.now()
        val end = parts.find { it.startsWith("DTEND") }
            ?.substringAfter(":")
            ?.parse(dateTimePattern)
            ?: start.plus(10, ChronoUnit.MONTHS)
        val rule = parts.find { it.startsWith("RRULE") }?.substringAfter(":") ?: return emptyList()
        val recur = Recur<OffsetDateTime>(rule)
        return recur.getDates(
            start,
            start,
            end,
            stepCount
        )
    }
}