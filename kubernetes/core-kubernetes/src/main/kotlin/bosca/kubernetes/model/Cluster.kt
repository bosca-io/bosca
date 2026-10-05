package bosca.kubernetes.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Environment classification used to group clusters in the studio and to
 * gate destructive operations behind extra confirmation on PRODUCTION.
 */
@Serializable
enum class ClusterEnvironment {
    PRODUCTION, STAGING, DEVELOPMENT,
}

/**
 * Coarse health summary maintained by `kubernetes-controller` as it watches
 * the cluster. The controller updates this column on observed state changes;
 * a stale `lastSeenAt` plus `OK` health is a cue to surface the cluster as
 * unreachable in the UI without blowing away the last-known data.
 */
@Serializable
enum class ClusterHealth {
    OK, WARN, ERROR,
}

/**
 * A registered Kubernetes cluster. The kubeconfig backing the
 * connection is stored encrypted in `kubernetes.cluster_credential`
 * (see [bosca.kubernetes.service.ClusterCredentialService]) and is
 * never exposed through the GraphQL surface.
 *
 * The columns kept on this row are exactly those that drive the
 * studio's `ClusterSwitcher` and cluster list view; anything richer
 * (per-namespace caches, observed workload counts per kind, etc.)
 * lives in dedicated tables added by later migrations.
 */
@Serializable
data class Cluster(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val provider: String,
    val region: String,
    val environment: ClusterEnvironment,
    @ColumnName("server_version")
    val serverVersion: String = "",
    val health: ClusterHealth = ClusterHealth.OK,
    val nodes: Int = 0,
    val pods: Int = 0,
    @ColumnName("registered_at")
    @Contextual
    val registeredAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("last_seen_at")
    @Contextual
    val lastSeenAt: OffsetDateTime? = null,
    @ColumnName("deleted_at")
    @Contextual
    val deletedAt: OffsetDateTime? = null,
    @ColumnName("modified_at")
    @Contextual
    val modifiedAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    /**
     * Optimistic-locking version, bumped on every mutation. Clients pass the
     * value they last observed in update mutations; mismatch surfaces as
     * `OPTIMISTIC_LOCK_FAILED` at the resolver layer.
     */
    val version: Long = 0,
)

/**
 * Inputs for [bosca.kubernetes.service.ClusterService.register]. The
 * raw kubeconfig is encrypted on its way into Postgres and dropped
 * from memory as soon as the encrypted credential is committed.
 */
@Serializable
data class RegisterClusterInput(
    val name: String,
    val provider: String,
    val region: String,
    val environment: ClusterEnvironment,
    val kubeconfig: String,
)

/**
 * Inputs for [bosca.kubernetes.service.ClusterService.update]. Both
 * fields are optional — null preserves the current value. Kubeconfig
 * rotation happens through a dedicated mutation so the audit log
 * captures the action distinctly.
 */
@Serializable
data class UpdateClusterInput(
    val name: String? = null,
    val environment: ClusterEnvironment? = null,
)
