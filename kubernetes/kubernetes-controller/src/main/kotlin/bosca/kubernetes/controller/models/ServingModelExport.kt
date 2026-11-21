package bosca.kubernetes.controller.models

import kotlinx.serialization.Serializable

/** One exported context generation discovered through Bosca's artifact-authorized API. */
@Serializable
data class ServingModelExport(
    val contextId: String,
    val version: Long,
    val selectionRevision: Long,
    val status: String,
    val contentModelName: String,
    val personalizedModelName: String,
    val personalized: Boolean,
) {
    val models: Map<String, Long> get() = buildMap {
        put(contentModelName, version)
        if (personalized) put(personalizedModelName, version)
    }
}
