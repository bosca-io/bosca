package bosca.collaboration.federation

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A remote Bosca instance that participates in federated messaging.
 * Peers communicate via NATS leaf nodes for message transport and
 * a REST API for federation handshakes.
 */
@Serializable
data class FederationPeer(
    @Contextual
    val id: UUID,
    val name: String,
    @ColumnName("nats_url")
    val natsUrl: String,
    @ColumnName("api_url")
    val apiUrl: String,
    val active: Boolean = true,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime? = null,
)
