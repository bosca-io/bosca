package bosca.ecommerce.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/** Payload for the periodic shipment-tracking sweep (no parameters — it polls every in-flight shipment). */
@Serializable
class ShipmentTrackingSweepJob : IJobDefinition
