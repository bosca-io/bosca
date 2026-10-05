package bosca.analytics.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Lifecycle state of an [ErrorGroup]. The pipeline opens new groups on
 * the first occurrence of a fingerprint and flips resolved groups back
 * to [OPEN] on regression (a new occurrence after the group was marked
 * resolved). Operators can also explicitly mark groups [IGNORED] to
 * suppress them from default views without losing event history.
 *
 * The Kotlin constants are UPPERCASE to match the framework's
 * [EnumMapper] convention (which uppercases the postgres column value
 * before calling `valueOf`). On write, [EnumMapper.bind] lowercases
 * `name` so that the bound value matches the postgres `error_group_status`
 * enum (which declares `open` / `resolved` / `ignored`). The GraphQL
 * schema's `ErrorGroupStatus` values line up 1:1 with these constants.
 */
@DbMapper(ErrorGroupStatusMapper::class)
@Serializable
enum class ErrorGroupStatus {
    OPEN,
    RESOLVED,
    IGNORED,
}

/**
 * Custom [EnumMapper] for [ErrorGroupStatus] matching the sibling
 * convention used by `FormSubmissionStatusMapper`, `GuideTypeMapper`,
 * `DataTypeMapper`, and so on. The explicit `.uppercase()` is defensive;
 * the base `map` already uppercases the column value before invoking
 * this allocator.
 */
object ErrorGroupStatusMapper :
    EnumMapper<ErrorGroupStatus>({ ErrorGroupStatus.valueOf(it.uppercase()) })
