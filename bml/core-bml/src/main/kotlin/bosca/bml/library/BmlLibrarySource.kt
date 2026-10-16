package bosca.bml.library

import kotlinx.serialization.Serializable

/** Original BML declaration in the matching sources artifact; positions are one-based. */
@Serializable
data class BmlLibrarySource(val entry: String, val line: Int, val column: Int, val digest: String)
