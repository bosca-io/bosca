package bosca.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies serialization and default-value behaviour of [RestoreJob],
 * the payload submitted to the job queue when a restore is initiated.
 */
class RestoreJobTest {

    private val json = Json {
        encodeDefaults = true
    }

    /**
     * A fully populated [RestoreJob] must survive a JSON round-trip so
     * the queue can persist and reconstruct job payloads accurately.
     */
    @Test
    fun serializationRoundTrip() {
        val job = RestoreJob(
            backupPath = "/backups/2026-03-17/full.zip",
            conflictStrategy = ConflictStrategy.OVERWRITE
        )
        val encoded = json.encodeToString(job)
        val decoded = json.decodeFromString<RestoreJob>(encoded)
        assertEquals(job, decoded)
    }

    /**
     * The default conflict strategy should be SKIP, preventing accidental
     * data overwrites when no explicit strategy is provided.
     */
    @Test
    fun defaultConflictStrategyIsSkip() {
        val job = RestoreJob(backupPath = "/backups/latest.zip")
        assertEquals(ConflictStrategy.SKIP, job.conflictStrategy)
    }

    /**
     * Each conflict strategy value must be preserved through a round-trip
     * to ensure the restore executor receives the intended behaviour.
     */
    @Test
    fun allConflictStrategiesRoundTrip() {
        for (strategy in ConflictStrategy.entries) {
            val job = RestoreJob(backupPath = "/test/path.zip", conflictStrategy = strategy)
            val encoded = json.encodeToString(job)
            val decoded = json.decodeFromString<RestoreJob>(encoded)
            assertEquals(strategy, decoded.conflictStrategy, "Round-trip failed for strategy $strategy")
        }
    }
}
