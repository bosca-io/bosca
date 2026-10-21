package bosca.bml.library

import kotlinx.serialization.Serializable

/** Compiled ESM entry identified by its complete source and package resolution graph. */
@Serializable
data class BmlLibraryClientDependency(
    val digest: String,
    val path: String,
)
