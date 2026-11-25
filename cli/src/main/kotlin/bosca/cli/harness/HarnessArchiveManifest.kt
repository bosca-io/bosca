package bosca.cli.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class HarnessArchiveManifest(val formatVersion: Int = 1, val policy: String, val metadata: JsonObject, val entries: Map<String, HarnessArchiveEntry>)
