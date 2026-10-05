package bosca.ecommerce.jobs

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlin.uuid.ExperimentalUuidApi

/**
 * Round-trip serialization coverage for the periodic-sweep job payloads. These `@Serializable`
 * parameterless `IJobDefinition` types serialize over the JobQueue (kotlinx.serialization), so their
 * generated serializers must round-trip cleanly. Uses explicit `.serializer()` (GraalVM native-safe)
 * with the platform's contextual UUID serializer, matching `PrimitivesSerializationTest`.
 */
@OptIn(ExperimentalUuidApi::class)
class JobPayloadSerializationTest {

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUID::class, UUIDSerializer()) }
    }

    private fun <T> roundTrip(serializer: KSerializer<T>, value: T): T =
        json.decodeFromString(serializer, json.encodeToString(serializer, value))

    @Test
    fun `CartExpirationSweepJob round-trips`() {
        assertTrue(roundTrip(CartExpirationSweepJob.serializer(), CartExpirationSweepJob()) is CartExpirationSweepJob)
    }

    @Test
    fun `ShipmentTrackingSweepJob round-trips`() {
        assertTrue(roundTrip(ShipmentTrackingSweepJob.serializer(), ShipmentTrackingSweepJob()) is ShipmentTrackingSweepJob)
    }

    @Test
    fun `SubscriptionRenewalJob round-trips`() {
        assertTrue(roundTrip(SubscriptionRenewalJob.serializer(), SubscriptionRenewalJob()) is SubscriptionRenewalJob)
    }
}
