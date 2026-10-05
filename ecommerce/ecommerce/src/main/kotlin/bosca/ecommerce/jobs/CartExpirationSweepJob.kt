package bosca.ecommerce.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/** Payload for the periodic cart-expiration sweep (no parameters — the sweep drains all expired carts). */
@Serializable
class CartExpirationSweepJob : IJobDefinition
