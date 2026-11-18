package bosca.bml.library

import kotlinx.serialization.Serializable

/** A resource path and its content digest inside a compiled client artifact. */
@Serializable
data class BmlAssetFile(val path: String, val digest: String)
