package bosca.kubernetes.model

import kotlinx.serialization.Serializable

/**
 * CloudNativePG `Cluster` (postgresql.cnpg.io/v1). Wire shape matches
 * the GraphQL `CnpgCluster` type.
 *
 * `backupSchedule` is sourced from the first `ScheduledBackup` that
 * targets this cluster (CNPG models the schedule as a separate CRD,
 * not as a field on the Cluster itself).
 *
 * `lastBackup` is the most recent successful backup's timestamp,
 * rendered relatively. Empty when no successful backup exists.
 */
@Serializable
data class K8sCnpgCluster(
    val name: String,
    val namespace: String,
    val instances: Int,
    val primary: String,
    val postgresVersion: String,
    val image: String,
    val status: String,
    val statusKind: WorkloadStatus,
    val age: String,
    val backupSchedule: String,
    val lastBackup: String,
)

@Serializable
data class CnpgClustersResponse(val items: List<K8sCnpgCluster>)

/**
 * One CNPG instance — a postgres pod managed by the operator. Sourced
 * from the `cnpg.io/cluster`-labelled pods plus the node and PVC each
 * one binds to. Replication lag, disk-used %, and live connection
 * counts are intentionally absent: they are postgres-internal metrics
 * with no source in the kubernetes API.
 */
@Serializable
data class K8sCnpgInstance(
    val name: String,
    /** `"primary"`, `"replica"`, or `""` when the role label is absent. */
    val role: String,
    /** Display status: `"Ready"` when every container is ready, else the pod phase or stuck reason. */
    val status: String,
    val ready: Boolean,
    val node: String,
    /** Availability zone of [node], or `""` when the node carries no zone label. */
    val zone: String,
    /** Bound PVC capacity (e.g. `"100Gi"`), or `""` when no matching PVC is found. */
    val pvcSize: String,
    val restarts: Int,
    val age: String,
)

/**
 * One CNPG `Backup` CRD instance. Size is omitted — CNPG does not
 * expose backup size on the Backup resource.
 */
@Serializable
data class K8sCnpgBackup(
    val name: String,
    /** Backup method: `"barmanObjectStore"`, `"volumeSnapshot"`, `"plugin"`, or `""`. */
    val method: String,
    /** Raw `status.phase` (e.g. `"completed"`, `"running"`, `"failed"`). */
    val phase: String,
    val statusKind: WorkloadStatus,
    /** `status.startedAt` formatted relatively, or `""`. */
    val started: String,
    /** `status.stoppedAt` formatted relatively, or `""` when still running. */
    val completed: String,
    /** Elapsed start→stop (e.g. `"12m"`), or `""` when unknown. */
    val duration: String,
)

/** A single `spec.postgresql.parameters` entry. */
@Serializable
data class K8sCnpgParameter(val key: String, val value: String)

/**
 * Full detail for one CNPG cluster: the summary row plus topology,
 * backup history, tuned parameters, and storage/backup spec. Backs the
 * GraphQL `CnpgClusterDetail` type.
 */
@Serializable
data class K8sCnpgClusterDetail(
    val cluster: K8sCnpgCluster,
    /** `spec.storage.size` (e.g. `"100Gi"`), or `""`. */
    val storageSize: String,
    /** `spec.storage.storageClass`, or `""` when the cluster uses the default class. */
    val storageClass: String,
    /** `spec.backup.barmanObjectStore.destinationPath`, or `""` when backups aren't configured. */
    val backupDestinationPath: String,
    /** `spec.backup.retentionPolicy` (e.g. `"30d"`), or `""`. */
    val backupRetention: String,
    val instances: List<K8sCnpgInstance>,
    val backups: List<K8sCnpgBackup>,
    val parameters: List<K8sCnpgParameter>,
)

/** Wraps the optional detail so a missing cluster maps to `detail = null`. */
@Serializable
data class CnpgClusterDetailResponse(val detail: K8sCnpgClusterDetail? = null)
