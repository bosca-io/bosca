package bosca.calendar.service

import bosca.calendar.model.CalendarEvent
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.fortuna.ical4j.model.Recur
import org.slf4j.LoggerFactory

/**
 * Expands recurring [CalendarEvent] masters into the concrete instance start
 * times that fall inside a half-open `[from, to)` window, applying the row's
 * EXDATE list. Wraps ical4j's [Recur] so callers don't have to deal with the
 * library directly.
 *
 * A safety cap prevents pathological RRULEs (no UNTIL, no COUNT, daily forever
 * over a multi-year window) from generating millions of dates per request.
 */
object RecurrenceExpander {

    private val log = LoggerFactory.getLogger(RecurrenceExpander::class.java)

    /** Hard upper bound on instances generated per master per query. */
    const val MAX_OCCURRENCES: Int = 1_000

    /**
     * Returns the start times of every occurrence of [master] that falls in
     * the half-open range `[from, to)`. Ordered ascending. Excluded
     * occurrences (per [CalendarEvent.exdates]) are filtered out.
     */
    fun expand(master: CalendarEvent, from: OffsetDateTime, to: OffsetDateTime): List<OffsetDateTime> {
        val rrule = master.rrule ?: return listOf(master.startsAt).filter { it < to && master.endsAt >= from }
        if (rrule.isBlank()) return listOf(master.startsAt).filter { it < to && master.endsAt >= from }

        val recur = try {
            Recur<OffsetDateTime>(rrule)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid RRULE: $rrule", e)
        }

        val seed = master.startsAt
        val periodStart = if (from.isBefore(seed)) seed else from
        if (!periodStart.isBefore(to)) return emptyList()

        val raw = recur.getDates(seed, periodStart, to, MAX_OCCURRENCES)
        if (raw.isEmpty()) return emptyList()

        val excluded = master.exdates.toExdateSet()
        return raw.asSequence()
            .filter { it !in excluded }
            .toList()
    }

    /**
     * Decides whether a given start time matches an occurrence of [master],
     * used to validate "edit/delete this occurrence" operations against the
     * actual recurrence rule rather than trusting client input.
     */
    fun isOccurrenceOf(master: CalendarEvent, candidate: OffsetDateTime): Boolean {
        if (master.rrule.isNullOrBlank()) return master.startsAt == candidate
        if (candidate.isBefore(master.startsAt)) return false
        // Ask ical4j for occurrences from the seed up to (and including) the candidate;
        // if the candidate is in the result the RRULE produces it.
        val recur = try {
            Recur<OffsetDateTime>(master.rrule)
        } catch (e: Exception) {
            log.error("Invalid RRULE: ${master.rrule}", e)
            return false
        }
        val window = candidate.plusSeconds(1)
        val dates = recur.getDates(master.startsAt, master.startsAt, window, MAX_OCCURRENCES)
        return dates.any { it == candidate }
    }

    /**
     * Returns a new EXDATE JSON array containing every existing entry plus
     * [occurrenceAt]. Idempotent.
     */
    fun addExdate(existing: kotlinx.serialization.json.JsonElement?, occurrenceAt: OffsetDateTime): JsonArray {
        val current = existing.toExdateSet()
        if (occurrenceAt in current) return existing.toExdateArray()
        val merged = current + occurrenceAt
        return JsonArray(merged.sorted().map { JsonPrimitive(it.toString()) })
    }

    /** Returns the EXDATE JSON array with [occurrenceAt] removed; null when the result is empty. */
    fun removeExdate(existing: kotlinx.serialization.json.JsonElement?, occurrenceAt: OffsetDateTime): JsonArray? {
        val current = existing.toExdateSet()
        if (occurrenceAt !in current) return existing.toExdateArray().takeIf { it.isNotEmpty() }
        val remaining = current - occurrenceAt
        if (remaining.isEmpty()) return null
        return JsonArray(remaining.sorted().map { JsonPrimitive(it.toString()) })
    }

    /**
     * Returns the EXDATE list trimmed to entries strictly before [boundary].
     * Used when splitting or ending a series, since EXDATEs at or after the
     * cut belong to the (deleted or successor) tail of the series.
     */
    fun exdatesBefore(existing: kotlinx.serialization.json.JsonElement?, boundary: OffsetDateTime): JsonArray? {
        val current = existing.toExdateSet()
        if (current.isEmpty()) return null
        val kept = current.filter { it.isBefore(boundary) }
        if (kept.isEmpty()) return null
        return JsonArray(kept.sorted().map { JsonPrimitive(it.toString()) })
    }

    /**
     * Returns the EXDATE list trimmed to entries that satisfy [predicate].
     * Used when a master's recurrence rule changes and EXDATEs that no longer
     * line up with a generated occurrence become orphans.
     */
    fun exdatesMatching(
        existing: kotlinx.serialization.json.JsonElement?,
        predicate: (OffsetDateTime) -> Boolean
    ): JsonArray? {
        val current = existing.toExdateSet()
        if (current.isEmpty()) return null
        val kept = current.filter(predicate)
        if (kept.isEmpty()) return null
        return JsonArray(kept.sorted().map { JsonPrimitive(it.toString()) })
    }

    /**
     * Returns a copy of [rrule] with `UNTIL=` set to the supplied bound. Any
     * existing UNTIL is replaced; COUNT is removed because the two are
     * mutually exclusive in RFC 5545. Used when "this and following" splits a
     * series at an occurrence.
     */
    fun withUntil(rrule: String, until: OffsetDateTime): String {
        val untilToken = "UNTIL=" + until.toUtc().format(UTC_BASIC)
        val parts = rrule.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val rebuilt = mutableListOf<String>()
        var replaced = false
        for (part in parts) {
            val key = part.substringBefore('=').uppercase()
            when (key) {
                "UNTIL" -> {
                    rebuilt += untilToken
                    replaced = true
                }
                "COUNT" -> Unit // strip COUNT, mutually exclusive with UNTIL
                else -> rebuilt += part
            }
        }
        if (!replaced) rebuilt += untilToken
        return rebuilt.joinToString(";")
    }

    private fun OffsetDateTime.toUtc(): OffsetDateTime = withOffsetSameInstant(java.time.ZoneOffset.UTC)

    private val UTC_BASIC = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    private fun kotlinx.serialization.json.JsonElement?.toExdateSet(): Set<OffsetDateTime> {
        val arr = this as? JsonArray ?: return emptySet()
        return arr.mapNotNullTo(linkedSetOf()) { entry ->
            val text = (entry as? JsonPrimitive)?.contentOrNull ?: return@mapNotNullTo null
            runCatching { OffsetDateTime.parse(text) }
                .onFailure { log.warn("Failed to parse EXDATE entry '{}': {}", text, it.message) }
                .getOrNull()
        }
    }

    private fun kotlinx.serialization.json.JsonElement?.toExdateArray(): JsonArray =
        (this as? JsonArray) ?: JsonArray(emptyList())
}
