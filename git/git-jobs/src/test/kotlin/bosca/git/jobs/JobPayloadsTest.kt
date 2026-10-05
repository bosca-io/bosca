package bosca.git.jobs

import bosca.serialization.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

/**
 * Value semantics + serialization round-trips for every git-jobs payload, so the
 * compiler-generated `equals`/`hashCode`/`copy`/serializer members are exercised
 * (both the default and non-default field arms).
 */
class JobPayloadsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun <T> roundTrip(serializer: KSerializer<T>, value: T) {
        assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
    }

    @Test
    fun `single-id payloads`() {
        val id = UUID.random()
        val other = UUID.random()

        roundTrip(RepositoryGcJob.serializer(), RepositoryGcJob(id))
        assertEquals(RepositoryGcJob(id), RepositoryGcJob(id))
        assertEquals(RepositoryGcJob(id).hashCode(), RepositoryGcJob(id).hashCode())
        assertNotEquals(RepositoryGcJob(id), RepositoryGcJob(other))

        roundTrip(WebhookDeliveryJob.serializer(), WebhookDeliveryJob(id))
        assertNotEquals(WebhookDeliveryJob(id), WebhookDeliveryJob(other))
    }

    @Test
    fun `nullable-id payloads cover default and set arms`() {
        val id = UUID.random()

        roundTrip(RepositoryPurgeJob.serializer(), RepositoryPurgeJob())
        roundTrip(RepositoryPurgeJob.serializer(), RepositoryPurgeJob(id))
        assertNotEquals(RepositoryPurgeJob(), RepositoryPurgeJob(id))
        assertEquals(RepositoryPurgeJob(id), RepositoryPurgeJob(id).copy())

        roundTrip(RepositoryBackupJob.serializer(), RepositoryBackupJob())
        roundTrip(RepositoryBackupJob.serializer(), RepositoryBackupJob(id))
        assertNotEquals(RepositoryBackupJob(), RepositoryBackupJob(id))
    }

    @Test
    fun `no-field marker payloads`() {
        // Markers are plain (non-data) classes: assert the serializer round-trips to a
        // valid instance rather than to object equality.
        assertNotNull(
            json.decodeFromString(
                RepositoryMaintenanceJob.serializer(),
                json.encodeToString(RepositoryMaintenanceJob.serializer(), RepositoryMaintenanceJob()),
            )
        )
        assertNotNull(
            json.decodeFromString(
                DfsPackReapJob.serializer(),
                json.encodeToString(DfsPackReapJob.serializer(), DfsPackReapJob()),
            )
        )
    }

    @Test
    fun `storage-carrying index payloads`() {
        roundTrip(ReindexAllJob.serializer(), ReindexAllJob())
        roundTrip(RepositoryIndexJob.serializer(), RepositoryIndexJob())
        val id = UUID.random()
        roundTrip(RepositoryIndexJob.serializer(), RepositoryIndexJob(repositoryId = id, deleteOnly = true))
        assertNotEquals(RepositoryIndexJob(), RepositoryIndexJob(repositoryId = id))
        assertNotEquals(RepositoryIndexJob(), RepositoryIndexJob(deleteOnly = true))
        assertEquals(RepositoryIndexJob(repositoryId = id), RepositoryIndexJob(repositoryId = id).copy())
    }

    @Test
    fun `required fields are enforced on decode`() {
        val id = UUID.random()
        assertFailsWith<Exception> { json.decodeFromString(RepositoryGcJob.serializer(), "{}") }
        assertFailsWith<Exception> { json.decodeFromString(WebhookDeliveryJob.serializer(), "{}") }
        assertFailsWith<Exception> { json.decodeFromString(ContentChangeWatcherJob.serializer(), """{"repositoryId":"$id"}""") }
    }

    @Test
    fun `optional fields encode when defaults are requested`() {
        val enc = Json { encodeDefaults = true }
        // Exercises the shouldEncodeElementDefault arm for the nullable/default payloads.
        roundTrip(RepositoryPurgeJob.serializer(), RepositoryPurgeJob())
        enc.encodeToString(RepositoryPurgeJob.serializer(), RepositoryPurgeJob())
        enc.encodeToString(RepositoryBackupJob.serializer(), RepositoryBackupJob())
        enc.encodeToString(ReindexAllJob.serializer(), ReindexAllJob())
        enc.encodeToString(RepositoryIndexJob.serializer(), RepositoryIndexJob())
        assertEquals(
            RepositoryIndexJob(deleteOnly = true),
            enc.decodeFromString(RepositoryIndexJob.serializer(), enc.encodeToString(RepositoryIndexJob.serializer(), RepositoryIndexJob(deleteOnly = true))),
        )
    }

    @Test
    fun `hashCode covers null and present optional ids`() {
        val id = UUID.random()
        // Exercises the nullable-field hashCode arm (`repositoryId?.hashCode() ?: 0`) both ways.
        RepositoryPurgeJob().hashCode()
        RepositoryPurgeJob(id).hashCode()
        RepositoryBackupJob().hashCode()
        RepositoryBackupJob(id).hashCode()
        RepositoryIndexJob().hashCode()
        RepositoryIndexJob(repositoryId = id).hashCode()
        assertNotEquals(RepositoryPurgeJob().hashCode(), RepositoryPurgeJob(id).hashCode())
    }

    @Test
    fun `equals handles identity and type mismatch`() {
        val id = UUID.random()
        for (job in listOf<Any>(RepositoryGcJob(id), RepositoryBackupJob(id), RepositoryPurgeJob(id), WebhookDeliveryJob(id), RepositoryIndexJob(repositoryId = id))) {
            assertEquals(job, job)          // reference-identity arm
            assertNotEquals(job, "not a job") // type-mismatch arm
        }
    }

    @Test
    fun `multi-field payloads`() {
        val id = UUID.random()
        val ccw = ContentChangeWatcherJob(id, "refs/heads/main", "a".repeat(40), "b".repeat(40))
        roundTrip(ContentChangeWatcherJob.serializer(), ccw)
        assertEquals(ccw, ccw.copy())
        assertNotEquals(ccw, ccw.copy(ref = "refs/heads/dev"))
        assertNotEquals(ccw, ccw.copy(beforeSha = "c".repeat(40)))
        assertNotEquals(ccw, ccw.copy(afterSha = "d".repeat(40)))
        assertNotEquals(ccw, ccw.copy(repositoryId = UUID.random()))

    }
}
