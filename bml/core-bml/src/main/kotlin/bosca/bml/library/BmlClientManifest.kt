package bosca.bml.library

import kotlinx.serialization.Serializable

/** Whitelist of packaged application assets; paths are relative to the application's resource root. */
@Serializable
data class BmlClientManifest(val formatVersion: Int = 1, val files: List<BmlAssetFile>)
