package bosca.backup

import kotlinx.serialization.Serializable

/**
 * Determines how the restore process handles pre-existing records that
 * conflict with data in the backup archive.
 */
@Serializable
enum class ConflictStrategy {
    /** Skip records that already exist, leaving the current data untouched. */
    SKIP,
    /** Overwrite existing records with data from the backup archive. */
    OVERWRITE,
    /** Abort the entire restore if any conflicts are detected. */
    FAIL
}
