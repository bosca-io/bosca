package bosca.kubernetes.controller.util

import bosca.kubernetes.model.K8sPvc
import bosca.kubernetes.model.K8sStorageClass
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.storage.StorageClass

/**
 * Maps a fabric8 [StorageClass] into our wire shape.
 *
 *  * `isDefault` honours both the canonical
 *    `storageclass.kubernetes.io/is-default-class=true` annotation
 *    and the legacy `beta.kubernetes.io/...` form some older clusters
 *    still use.
 *  * `parameters` is flattened to a single-line `k=v, k=v` string —
 *    matches the existing table column expectation.
 */
fun StorageClass.toWire(): K8sStorageClass {
    val labels = metadata?.annotations.orEmpty()
    val isDefault =
        labels["storageclass.kubernetes.io/is-default-class"] == "true" ||
            labels["storageclass.beta.kubernetes.io/is-default-class"] == "true"
    val params = parameters.orEmpty().entries.joinToString(", ") { "${it.key}=${it.value}" }
    return K8sStorageClass(
        name = metadata?.name.orEmpty(),
        provisioner = provisioner.orEmpty(),
        reclaim = reclaimPolicy ?: "Delete",
        binding = volumeBindingMode ?: "Immediate",
        isDefault = isDefault,
        age = formatAge(metadata?.creationTimestamp),
        parameters = params,
    )
}

/**
 * Maps a fabric8 [PersistentVolumeClaim] into our wire shape.
 *
 *  * `accessMode` shows the first listed mode abbreviated
 *    (`ReadWriteOnce → RWO`) — multi-mode PVCs are rare in admin
 *    contexts but if present, we list all separated by `,`.
 *  * `capacity` shows `requestedSize → actualSize` only when the two
 *    differ (in-flight resize); otherwise just the actual size.
 *  * `usedPercent` is null until the metrics integration lands.
 *  * `workload` is the owner-ref name when present — common when a
 *    StatefulSet templates per-pod PVCs.
 */
fun PersistentVolumeClaim.toWire(): K8sPvc {
    val requested = spec?.resources?.requests?.get("storage")?.toString()
    val actual = status?.capacity?.get("storage")?.toString()
    val capacity = when {
        requested != null && actual != null && requested != actual -> "$requested → $actual"
        actual != null -> actual
        requested != null -> requested
        else -> "-"
    }
    val accessModes = (spec?.accessModes ?: emptyList()).joinToString(",") { abbreviateAccessMode(it) }
    val ownerName = metadata?.ownerReferences?.firstOrNull()?.name
    return K8sPvc(
        id = metadata?.uid ?: "PersistentVolumeClaim/${metadata?.namespace}/${metadata?.name}",
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        status = status?.phase ?: "Pending",
        volume = spec?.volumeName.orEmpty(),
        capacity = capacity,
        accessMode = accessModes.ifBlank { "-" },
        storageClass = spec?.storageClassName ?: "-",
        age = formatAge(metadata?.creationTimestamp),
        usedPercent = null,
        workload = ownerName,
    )
}

/** k8s spells these out in full; admins read them everywhere abbreviated. */
private fun abbreviateAccessMode(mode: String): String = when (mode) {
    "ReadWriteOnce" -> "RWO"
    "ReadWriteMany" -> "RWX"
    "ReadOnlyMany" -> "ROX"
    "ReadWriteOncePod" -> "RWOP"
    else -> mode
}
