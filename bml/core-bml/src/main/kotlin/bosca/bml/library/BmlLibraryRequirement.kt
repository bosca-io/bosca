package bosca.bml.library

import kotlinx.serialization.Serializable

/** The exact binary BML contract and exported entry points used while compiling another library. */
@Serializable
data class BmlLibraryRequirement(
    val library: String,
    val version: String,
    val digest: String,
    val components: List<String>,
)
