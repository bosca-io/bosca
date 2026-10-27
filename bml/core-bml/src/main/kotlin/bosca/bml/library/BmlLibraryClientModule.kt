package bosca.bml.library

import kotlinx.serialization.Serializable

/** A producer's rebundleable ESM entry and any separately emitted stylesheets. */
@Serializable
data class BmlLibraryClientModule(val path: String, val styles: List<String> = emptyList())
