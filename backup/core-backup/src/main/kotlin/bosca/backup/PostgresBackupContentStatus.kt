package bosca.backup

import kotlinx.serialization.Serializable

/** Retained-object inspection outcomes; VERIFIED establishes integrity, not database snapshot coverage. */
@Serializable
enum class PostgresBackupContentStatus {
    VERIFIED,
    UNRESOLVED,
    NOT_RETAINED,
    AMBIGUOUS,
    LENGTH_MISMATCH,
    MISSING,
    CORRUPT,
}
