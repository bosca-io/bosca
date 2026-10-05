package bosca.collaboration.federation

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Controls the direction of message synchronization between federated
 * Bosca instances for a specific channel pairing.
 */
@DbMapper(FederationSyncDirectionMapper::class)
@Serializable
enum class FederationSyncDirection {
    BIDIRECTIONAL,
    INBOUND,
    OUTBOUND
}

object FederationSyncDirectionMapper : EnumMapper<FederationSyncDirection>({ FederationSyncDirection.valueOf(it.uppercase()) })
