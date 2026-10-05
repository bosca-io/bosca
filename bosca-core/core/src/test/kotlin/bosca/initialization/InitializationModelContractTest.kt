package bosca.initialization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class InitializationModelContractTest {

    private fun assertIdentityAndTypeBranches(value: Any) {
        assertEquals(value, value)
        assertFalse(value.equals(Any()))
        assertFalse(value.equals(null))
    }

    @Test
    fun `readiness and health models preserve all resource states`() {
        val ready = ReadyResponse("ready")
        val cache = CacheResource("metadata")
        val database = DatabaseResource("primary", 10, 4, 2, true)
        val health = HealthResponse(listOf(database), listOf(cache), true)

        listOf(ready, cache, database, health).forEach(::assertIdentityAndTypeBranches)
        assertNotEquals(ready, ready.copy(status = "not ready"))
        assertNotEquals(cache, cache.copy(name = "other"))
        assertNotEquals(database, database.copy(hasAvailableConnections = false))
        assertNotEquals(health, health.copy(ok = false))
    }
}
