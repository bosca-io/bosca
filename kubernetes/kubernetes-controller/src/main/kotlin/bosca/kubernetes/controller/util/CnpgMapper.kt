package bosca.kubernetes.controller.util

import bosca.kubernetes.model.K8sCnpgBackup
import bosca.kubernetes.model.K8sCnpgCluster
import bosca.kubernetes.model.K8sCnpgClusterDetail
import bosca.kubernetes.model.K8sCnpgInstance
import bosca.kubernetes.model.K8sCnpgParameter
import bosca.kubernetes.model.WorkloadStatus
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.Pod
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Maps a CNPG `postgresql.cnpg.io/v1.Cluster` (delivered as a
 * [GenericKubernetesResource]) into our wire shape.
 *
 * Status mapping prioritises a small set of well-known phase strings
 * CNPG uses. The "long form" `status` field is the raw phase; the
 * coarse `statusKind` lets the studio's badge render the right color
 * without each consumer reimplementing the heuristic.
 */
@Suppress("UNCHECKED_CAST")
fun GenericKubernetesResource.toCnpgCluster(
    scheduleByCluster: Map<String, String>,
    lastBackupByCluster: Map<String, OffsetDateTime>,
    now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
): K8sCnpgCluster {
    val spec = additionalProperties["spec"] as? Map<String, Any?>
    val status = additionalProperties["status"] as? Map<String, Any?>

    val instances = (spec?.get("instances") as? Number)?.toInt() ?: 0
    val primary = (status?.get("currentPrimary") as? String).orEmpty()
    val image = (spec?.get("imageName") as? String).orEmpty()
    val postgresVersion = parseVersion(image)

    val phase = (status?.get("phase") as? String).orEmpty()
    val statusKind = mapPhase(phase)

    val key = "${metadata?.namespace.orEmpty()}/${metadata?.name.orEmpty()}"
    val backupSchedule = scheduleByCluster[key].orEmpty()
    val lastBackup = lastBackupByCluster[key]
        ?.let { formatRelativeTime(it.toString(), now) }
        ?: ""

    return K8sCnpgCluster(
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        instances = instances,
        primary = primary,
        postgresVersion = postgresVersion,
        image = image,
        status = phase,
        statusKind = statusKind,
        age = formatAge(metadata?.creationTimestamp),
        backupSchedule = backupSchedule,
        lastBackup = lastBackup,
    )
}

/**
 * Best-effort postgres-version parse from the container image tag.
 * Image conventions:
 *   * `ghcr.io/cloudnative-pg/postgresql:16.4`            → `16.4`
 *   * `registry.io/postgres:16.4-bookworm`                 → `16.4`
 *   * `internal/pg:custom`                                 → `` (empty)
 */
private fun parseVersion(image: String): String {
    if (image.isBlank()) return ""
    val tag = image.substringAfterLast(':', "").substringBefore('@')
    if (tag.isBlank() || tag == image) return ""
    return tag.substringBefore('-').takeIf { it.first().isDigit() } ?: ""
}

private fun mapPhase(phase: String): WorkloadStatus = when {
    phase.equals("Cluster in healthy state", ignoreCase = true) -> WorkloadStatus.OK
    phase.startsWith("Cluster in healthy", ignoreCase = true) -> WorkloadStatus.OK
    phase.contains("Failover", ignoreCase = true) -> WorkloadStatus.WARN
    phase.contains("Switchover", ignoreCase = true) -> WorkloadStatus.WARN
    phase.contains("Setting up", ignoreCase = true) -> WorkloadStatus.WARN
    phase.contains("Upgrading", ignoreCase = true) -> WorkloadStatus.WARN
    phase.contains("Failed", ignoreCase = true) -> WorkloadStatus.ERROR
    phase.contains("Unhealthy", ignoreCase = true) -> WorkloadStatus.ERROR
    phase.isBlank() -> WorkloadStatus.WARN     // status not yet observed
    else -> WorkloadStatus.OK
}

/**
 * Maps a CNPG instance [Pod] (selected by `cnpg.io/cluster=<name>`)
 * into our wire shape.
 *
 *  * `role` reads the operator's `cnpg.io/instanceRole` label first
 *    (older operators use bare `role`), and only falls back to
 *    comparing against [currentPrimary] when neither label is present.
 *    Trusting the label keeps the role correct mid-failover, when the
 *    Cluster status's `currentPrimary` can briefly lag the pods.
 *  * Ready/stuck-reason handling mirrors `PodMapper.toPodWire`: a known
 *    stuck waiting-reason (CrashLoopBackOff etc.) is surfaced over the
 *    coarse phase, and `ready` is true only when every container is.
 *  * [zoneByNode] and [pvcSizeByInstance] are pre-built by the route in
 *    one nodes/PVC list each, so this stays a pure constant-time map.
 */
fun Pod.toCnpgInstance(
    currentPrimary: String,
    zoneByNode: Map<String, String>,
    pvcSizeByInstance: Map<String, String>,
    now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
): K8sCnpgInstance {
    val name = metadata?.name.orEmpty()
    val labels = metadata?.labels.orEmpty()
    val role = labels["cnpg.io/instanceRole"]
        ?: labels["role"]
        ?: if (name.isNotEmpty() && name == currentPrimary) "primary" else "replica"

    val containers = spec?.containers.orEmpty()
    val containerStatuses = status?.containerStatuses.orEmpty()
    val readyCount = containerStatuses.count { it.ready == true }
    val totalCount = containers.size.coerceAtLeast(containerStatuses.size)
    val ready = totalCount > 0 && readyCount == totalCount
    val restarts = containerStatuses.sumOf { it.restartCount ?: 0 }

    val stuckReason = containerStatuses.asSequence()
        .mapNotNull { it.state?.waiting?.reason }
        .firstOrNull { it in STUCK_REASONS }
    val phase = status?.phase ?: "Unknown"
    val displayStatus = stuckReason ?: if (ready) "Ready" else phase

    val node = spec?.nodeName.orEmpty()
    return K8sCnpgInstance(
        name = name,
        role = role,
        status = displayStatus,
        ready = ready,
        node = node,
        zone = zoneByNode[node].orEmpty(),
        pvcSize = pvcSizeByInstance[name].orEmpty(),
        restarts = restarts,
        age = formatAge(metadata?.creationTimestamp, now),
    )
}

/**
 * Builds an instance row from the CNPG `Cluster` status alone, for when
 * the instance's pod can't be read — e.g. the kubeconfig can list the
 * `postgresql.cnpg.io` CRDs but not core `pods`. Node, zone, restarts,
 * age, and live readiness need the pod, so they're left blank/false;
 * name, role, and (when the PVC is readable) the bound volume size still
 * render, so the topology table lists the cluster's instances instead of
 * showing nothing.
 *
 * `role` prefers the operator's reported state (`isPrimary`) and falls
 * back to comparing against [currentPrimary]. `status` is `"Unknown"`
 * because pod-level health isn't observable here — the studio renders
 * that as a muted, non-alarming badge.
 */
fun cnpgInstanceFromStatus(
    name: String,
    isPrimaryReported: Boolean?,
    currentPrimary: String,
    pvcSize: String,
): K8sCnpgInstance = K8sCnpgInstance(
    name = name,
    role = when (isPrimaryReported) {
        true -> "primary"
        false -> "replica"
        null -> if (name.isNotEmpty() && name == currentPrimary) "primary" else "replica"
    },
    status = "Unknown",
    ready = false,
    node = "",
    zone = "",
    pvcSize = pvcSize,
    restarts = 0,
    age = "",
)

/**
 * Maps a CNPG `Backup` CRD into our wire shape. `started`/`completed`
 * are blank (not `"-"`) when the corresponding status timestamp is
 * absent, so the studio can render an empty cell for an in-flight or
 * never-run backup.
 */
@Suppress("UNCHECKED_CAST")
fun GenericKubernetesResource.toCnpgBackup(
    now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
): K8sCnpgBackup {
    val spec = additionalProperties["spec"] as? Map<String, Any?>
    val status = additionalProperties["status"] as? Map<String, Any?>
    val phase = (status?.get("phase") as? String).orEmpty()
    val startedAt = status?.get("startedAt") as? String
    val stoppedAt = status?.get("stoppedAt") as? String
    return K8sCnpgBackup(
        name = metadata?.name.orEmpty(),
        method = (spec?.get("method") as? String).orEmpty(),
        phase = phase,
        statusKind = mapBackupPhase(phase),
        started = if (!startedAt.isNullOrBlank()) formatRelativeTime(startedAt, now) else "",
        completed = if (!stoppedAt.isNullOrBlank()) formatRelativeTime(stoppedAt, now) else "",
        duration = formatBackupDuration(startedAt, stoppedAt),
    )
}

/**
 * Assembles full cluster detail from this `Cluster` CRD plus the
 * already-mapped [instances] and [backups]. Storage, backup target /
 * retention, and tuned parameters are read from the Cluster spec.
 */
@Suppress("UNCHECKED_CAST")
fun GenericKubernetesResource.toCnpgClusterDetail(
    summary: K8sCnpgCluster,
    instances: List<K8sCnpgInstance>,
    backups: List<K8sCnpgBackup>,
): K8sCnpgClusterDetail {
    val spec = additionalProperties["spec"] as? Map<String, Any?>
    val storage = spec?.get("storage") as? Map<String, Any?>
    val backup = spec?.get("backup") as? Map<String, Any?>
    val barman = backup?.get("barmanObjectStore") as? Map<String, Any?>
    val postgresql = spec?.get("postgresql") as? Map<String, Any?>
    val parameters = (postgresql?.get("parameters") as? Map<String, Any?>).orEmpty()

    return K8sCnpgClusterDetail(
        cluster = summary,
        storageSize = (storage?.get("size") as? String).orEmpty(),
        storageClass = (storage?.get("storageClass") as? String).orEmpty(),
        backupDestinationPath = (barman?.get("destinationPath") as? String).orEmpty(),
        backupRetention = (backup?.get("retentionPolicy") as? String).orEmpty(),
        instances = instances,
        backups = backups,
        parameters = parameters.entries
            .map { (k, v) -> K8sCnpgParameter(key = k, value = v?.toString().orEmpty()) }
            .sortedBy { it.key },
    )
}

private fun mapBackupPhase(phase: String): WorkloadStatus = when {
    phase.equals("completed", ignoreCase = true) -> WorkloadStatus.OK
    phase.contains("failed", ignoreCase = true) -> WorkloadStatus.ERROR
    phase.isBlank() -> WorkloadStatus.WARN
    else -> WorkloadStatus.WARN   // running / started / pending / walArchivingFailing etc.
}

/**
 * Compact two-unit elapsed time between a backup's start and stop, or
 * empty when either timestamp is missing or unparseable.
 */
private fun formatBackupDuration(startedAt: String?, stoppedAt: String?): String {
    if (startedAt.isNullOrBlank() || stoppedAt.isNullOrBlank()) return ""
    return try {
        val seconds = Duration.between(
            OffsetDateTime.parse(startedAt).toInstant(),
            OffsetDateTime.parse(stoppedAt).toInstant(),
        ).seconds
        if (seconds < 0) return ""
        when {
            seconds >= 3_600 -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
            seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    } catch (_: DateTimeParseException) {
        ""
    }
}

/**
 * Container waiting-reasons that mean "stuck" rather than "starting" —
 * surfaced over the coarse pod phase. Mirrors the set in
 * `PodMapper` (kept local so the two mappers stay independent).
 */
private val STUCK_REASONS = setOf(
    "CrashLoopBackOff",
    "ImagePullBackOff",
    "ErrImagePull",
    "CreateContainerConfigError",
    "InvalidImageName",
)
