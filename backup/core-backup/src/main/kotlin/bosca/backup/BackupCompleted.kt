package bosca.backup

import bosca.events.Event
import bosca.events.annotation.JobEvent
import kotlinx.serialization.Serializable

/**
 * A published backup available for a separate copy operation through an event-triggered Pipeline.
 * [storageKey] identifies its configured object store. [path] is the artifact directory containing
 * manifest.json and database.dump for POSTGRES, or the manifest file for OBJECTS. Object manifests
 * reference every required retained payload, including files shared with earlier backups.
 * [key] identifies the managed backup request. Replays may emit the same event again; consumers
 * identify an artifact by its type, storage identity and path and reuse completed copy work.
 * This event reports publication to [storageKey]; a subsequent external copy has its own outcome.
 */
@JobEvent(
    jobs = [],
    displayName = "Backup completed",
    description = "A PostgreSQL or object backup is published and ready to copy to another location.",
)
@Serializable
data class BackupCompleted(
    val key: String,
    val type: BackupType,
    val storageKey: String,
    val path: String,
) : Event {
    override fun identityKey(): Any = listOf(type, storageKey, path)
}
