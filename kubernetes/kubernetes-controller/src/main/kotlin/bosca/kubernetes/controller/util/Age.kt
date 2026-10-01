package bosca.kubernetes.controller.util

import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Formats a kubernetes-style age string (`60d`, `2h`, `45m`, `30s`)
 * from an ISO-8601 creation timestamp such as
 * `metadata.creationTimestamp`. Returns `"-"` for null or malformed
 * timestamps so the studio renders an empty placeholder instead of
 * propagating a parse failure into the GraphQL response.
 *
 * The granularity ladder matches `kubectl get`'s convention:
 * days > 1 → days, hours > 1 → hours, minutes > 1 → minutes, else
 * seconds. We deliberately don't render the multi-unit `1d2h`
 * form — the studio wants a single-token age for compact list cells.
 */
fun formatAge(creationTimestamp: String?, now: OffsetDateTime): String {
    if (creationTimestamp.isNullOrBlank()) return "-"
    val created = try {
        OffsetDateTime.parse(creationTimestamp)
    } catch (_: DateTimeParseException) {
        return "-"
    }
    val duration = Duration.between(created.toInstant(), now.toInstant())
    val seconds = duration.seconds.coerceAtLeast(0)
    val days = seconds / 86_400
    if (days > 0) return "${days}d"
    val hours = seconds / 3_600
    if (hours > 0) return "${hours}h"
    val minutes = seconds / 60
    if (minutes > 0) return "${minutes}m"
    return "${seconds}s"
}

/** Convenience for callers that want the age relative to "now in UTC". */
fun formatAge(creationTimestamp: String?): String =
    formatAge(creationTimestamp, OffsetDateTime.now(ZoneOffset.UTC))

/**
 * Formats an ISO-8601 timestamp as a human-friendly relative phrase
 * (`"2m ago"`, `"1h ago"`, `"just now"`). Used for the Event timeline
 * where the studio wants natural-language "when" strings; for the
 * table-cell age form, prefer [formatAge].
 */
fun formatRelativeTime(timestamp: String?, now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): String {
    if (timestamp.isNullOrBlank()) return "-"
    val parsed = try {
        OffsetDateTime.parse(timestamp)
    } catch (_: DateTimeParseException) {
        return "-"
    }
    val seconds = Duration.between(parsed.toInstant(), now.toInstant()).seconds.coerceAtLeast(0)
    if (seconds < 5) return "just now"
    val days = seconds / 86_400
    if (days > 0) return "${days}d ago"
    val hours = seconds / 3_600
    if (hours > 0) return "${hours}h ago"
    val minutes = seconds / 60
    if (minutes > 0) return "${minutes}m ago"
    return "${seconds}s ago"
}
