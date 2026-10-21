package bosca.backup

import kotlinx.serialization.Serializable

/** Source metadata selection outcomes, without asset transfer or database coverage guarantees. */
@Serializable
enum class PostgresBackupContentSelectionStatus {
    SELECTED,
    UNRESOLVED,
    MISSING,
    LENGTH_MISMATCH,
}
