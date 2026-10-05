package bosca.kubernetes.model

import kotlinx.serialization.Serializable

/**
 * A lightweight change notification for a watched kubernetes resource,
 * emitted on the `k8sResourcesWatch` GraphQL subscription.
 *
 * Deliberately metadata-only (no spec / status payload): the studio
 * uses these events purely as a refresh trigger — it re-runs the
 * existing list query for the affected kinds rather than reconciling
 * deltas client-side. Keeping the wire shape tiny lets one stream
 * cover many kinds without serialising whole objects per change.
 */
@Serializable
data class ResourceChangeEvent(
    /** The kind token the caller subscribed with (e.g. `Service`, `HelmRelease`, `postgresql.cnpg.io/Cluster`). */
    val kind: String,
    /** Namespace of the changed resource; null for cluster-scoped kinds. */
    val namespace: String? = null,
    val name: String,
    /** `ADDED` / `MODIFIED` / `DELETED`, mirroring the kubernetes watch action. */
    val action: String,
    /** ISO-8601 UTC timestamp the controller observed the change. */
    val timestamp: String,
)
