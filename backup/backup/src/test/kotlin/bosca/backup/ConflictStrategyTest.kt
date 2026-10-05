package bosca.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that the [ConflictStrategy] enum contains the expected members
 * and that its serialization contract is stable, since strategy values are
 * persisted inside job payloads and backup manifests.
 */
class ConflictStrategyTest {

    private val json = Json

    /**
     * The enum must expose exactly the three strategies that the restore
     * executor understands: SKIP, OVERWRITE, and FAIL.
     */
    @Test
    fun containsExpectedValues() {
        val names = ConflictStrategy.entries.map { it.name }.toSet()
        assertTrue(names.contains("SKIP"), "Missing SKIP strategy")
        assertTrue(names.contains("OVERWRITE"), "Missing OVERWRITE strategy")
        assertTrue(names.contains("FAIL"), "Missing FAIL strategy")
        assertEquals(3, ConflictStrategy.entries.size, "Unexpected number of strategies")
    }

    /**
     * Each strategy value must survive a JSON round-trip so that job
     * definitions stored in the queue can be deserialized correctly.
     */
    @Test
    fun serializationRoundTrip() {
        for (strategy in ConflictStrategy.entries) {
            val encoded = json.encodeToString(strategy)
            val decoded = json.decodeFromString<ConflictStrategy>(encoded)
            assertEquals(strategy, decoded, "Round-trip failed for $strategy")
        }
    }

    /**
     * The serialized form should be a quoted string matching the enum
     * constant name, ensuring human-readable JSON payloads.
     */
    @Test
    fun serializedFormMatchesEnumName() {
        for (strategy in ConflictStrategy.entries) {
            val encoded = json.encodeToString(strategy)
            assertEquals("\"${strategy.name}\"", encoded)
        }
    }
}
