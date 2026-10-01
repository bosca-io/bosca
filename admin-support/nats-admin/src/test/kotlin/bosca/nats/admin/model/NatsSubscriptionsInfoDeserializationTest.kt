package bosca.nats.admin.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class NatsSubscriptionsInfoDeserializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes subsz response`() {
        val body = """
        {
          "num_subscriptions": 450,
          "num_cache": 120,
          "num_inserts": 5000,
          "num_removes": 4550,
          "num_matches": 100000,
          "cache_hit_rate": 0.95,
          "max_fanout": 25,
          "avg_fanout": 3.2
        }
        """.trimIndent()

        val result = json.decodeFromString<NatsSubscriptionsInfo>(body)
        assertEquals(450L, result.numSubscriptions)
        assertEquals(120L, result.numCache)
        assertEquals(5000L, result.numInserts)
        assertEquals(4550L, result.numRemoves)
        assertEquals(100000L, result.numMatches)
        assertEquals(0.95, result.cacheHitRate, 0.001)
        assertEquals(25, result.maxFanout)
        assertEquals(3.2, result.avgFanout, 0.001)
    }
}
