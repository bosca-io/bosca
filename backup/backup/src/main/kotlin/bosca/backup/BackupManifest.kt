package bosca.backup

import kotlinx.serialization.Serializable

/**
 * Metadata describing the contents and structure of a backup archive.
 * Written as the last entry in the ZIP so that all record counts are
 * accurate at the time of serialization.
 */
@Serializable
data class BackupManifest(
    /** Schema version of this manifest format. Increment when the archive layout changes. */
    val version: Int = 1,
    /** Identifies the archive format; always "bosca-backup". */
    val format: String = "bosca-backup",
    /** ISO-8601 timestamp recording when the backup was initiated. */
    val createdAt: String,
    /** Record counts keyed by table name, populated during export. */
    val counts: MutableMap<String, Long> = mutableMapOf()
)
