package bosca.ecommerce.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/** Payload for the periodic inventory-sync sweep (no parameters — it pulls quantities for every syncable center due for a refresh). */
@Serializable
class InventorySyncSweepJob : IJobDefinition
