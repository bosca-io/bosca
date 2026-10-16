package bosca.bml.library

import kotlinx.serialization.Serializable

/** A component's compiled entry point, authoring contract, and dependency metadata. */
@Serializable
data class BmlLibraryComponent(
    val tag: String,
    val exported: Boolean,
    val renderer: String,
    val source: BmlLibrarySource,
    val props: List<BmlLibraryProp>,
    val hasDefaultSlot: Boolean,
    val dependencies: List<String>,
    val eagerDependencies: List<String>,
    val styles: String,
    val clientModule: String?,
    val hasServerState: Boolean,
    val hasClientBehavior: Boolean,
    val hasDeferredIslands: Boolean,
    val hasEagerFeatureFlags: Boolean,
    val renderRevision: String,
)
