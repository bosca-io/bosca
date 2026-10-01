package bosca.workops.model.worklog

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Visibility of an individual worklog entry. R15 acceptance. */
@Serializable
enum class WorklogVisibility { ALL, ROLE, PROFILES }

/**
 * R15 — `Task.timeSpentSeconds` is the sum of non-deleted worklogs.
 * The `*Mode` flag on the project decides how
 * `remainingEstimateSeconds` reacts to a worklog event:
 *
 *  - `AUTO_REDUCE`: `remaining := max(0, remaining - timeSpent)`
 *  - `LEAVE_ESTIMATE`: no change
 *  - `SET_TO_NEW_VALUE(input)`: `remaining := input`
 *  - `REDUCE_BY_VALUE(input)`: `remaining := max(0, remaining - input)`
 */
@Serializable
enum class WorklogEstimateAdjustmentMode {
    AUTO_REDUCE,
    LEAVE_ESTIMATE,
    SET_TO_NEW_VALUE,
    REDUCE_BY_VALUE,
}

@Serializable
data class WorkLog(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("task_id")
    @Contextual
    val taskId: UUID,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID,
    @ColumnName("time_spent_seconds")
    val timeSpentSeconds: Long,
    @ColumnName("started_at")
    @Contextual
    val startedAt: OffsetDateTime,
    val comment: String? = null,
    @ColumnName("worklog_visibility")
    val worklogVisibility: WorklogVisibility = WorklogVisibility.ALL,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("modified_at")
    @Contextual
    val modifiedAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("deleted_at")
    @Contextual
    val deletedAt: OffsetDateTime? = null,
)

/**
 * GraphQL input — duration is supplied as the YouTrack/Jira
 * shorthand `1w 2d 3h 30m`; the service decodes to seconds.
 */
@Serializable
data class WorkLogInput(
    val timeSpent: String,
    @Contextual
    val startedAt: OffsetDateTime,
    val comment: String? = null,
    val visibility: WorklogVisibility = WorklogVisibility.ALL,
    /**
     * Caller-supplied estimate adjustment override. When null, the
     * service reads `Project.worklogEstimateAdjustmentMode`. Used by
     * `SET_TO_NEW_VALUE` / `REDUCE_BY_VALUE` modes.
     */
    val estimateAdjustment: String? = null,
)

/**
 * Aggregated time totals for a parent (Sprint / Epic / Component).
 */
@Serializable
data class TimeAggregate(
    val totalEstimateSeconds: Long,
    val totalRemainingSeconds: Long,
    val totalSpentSeconds: Long,
    val taskCount: Long,
    val doneCount: Long,
)

/**
 * Parses the YouTrack/Jira shorthand into seconds. The accepted
 * units are `w` (week = 5×8h working days), `d` (day = 8h),
 * `h` (hour), `m` (minute), `s` (second). Whitespace separates
 * components; the parser is tolerant of single-component inputs
 * and case.
 */
object DurationShorthand {

    fun parseToSeconds(text: String): Long {
        if (text.isBlank()) return 0L
        val tokenRegex = Regex("(\\d+)([wdhms])", RegexOption.IGNORE_CASE)
        var total = 0L
        for (match in tokenRegex.findAll(text)) {
            val n = match.groupValues[1].toLong()
            val unit = match.groupValues[2].lowercase()
            total += when (unit) {
                "w" -> n * 5 * 8 * 3600 // 5 working days × 8h
                "d" -> n * 8 * 3600
                "h" -> n * 3600
                "m" -> n * 60
                "s" -> n
                else -> 0L
            }
        }
        require(total > 0L) { "duration shorthand must contain at least one positive component: '$text'" }
        return total
    }

    fun renderShorthand(seconds: Long): String {
        if (seconds <= 0L) return "0m"
        val weeks = seconds / (5 * 8 * 3600)
        var rem = seconds - weeks * (5 * 8 * 3600)
        val days = rem / (8 * 3600)
        rem -= days * (8 * 3600)
        val hours = rem / 3600
        rem -= hours * 3600
        val minutes = rem / 60
        rem -= minutes * 60
        val parts = buildList {
            if (weeks > 0) add("${weeks}w")
            if (days > 0) add("${days}d")
            if (hours > 0) add("${hours}h")
            if (minutes > 0) add("${minutes}m")
            if (rem > 0) add("${rem}s")
        }
        return if (parts.isEmpty()) "0m" else parts.joinToString(" ")
    }
}
