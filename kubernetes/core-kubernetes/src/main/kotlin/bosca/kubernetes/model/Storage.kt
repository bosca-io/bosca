package bosca.kubernetes.model

import kotlinx.serialization.Serializable

/**
 * Cluster-scoped StorageClass. Mirrors the GraphQL `StorageClass`
 * type. `isDefault` comes from the
 * `storageclass.kubernetes.io/is-default-class=true` annotation.
 */
@Serializable
data class K8sStorageClass(
    val name: String,
    val provisioner: String,
    val reclaim: String,
    val binding: String,
    val isDefault: Boolean,
    val age: String,
    val parameters: String,
)

/** Wire envelope for `GET /clusters/{id}/storageclasses`. */
@Serializable
data class StorageClassesResponse(val items: List<K8sStorageClass>)

/**
 * A PersistentVolumeClaim. `usedPercent` is null unless metrics are
 * collected for the bound PV — the studio renders the bar as empty
 * when null rather than misleading the operator with a fake 0%.
 */
@Serializable
data class K8sPvc(
    val id: String,
    val name: String,
    val namespace: String,
    val status: String,
    val volume: String,
    val capacity: String,
    val accessMode: String,
    val storageClass: String,
    val age: String,
    val usedPercent: Int? = null,
    val workload: String? = null,
)

/** Wire envelope for `GET /clusters/{id}/pvcs`. */
@Serializable
data class PvcsResponse(val items: List<K8sPvc>)
