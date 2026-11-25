package bosca.cli.harness

import kotlinx.serialization.Serializable

@Serializable
internal data class HarnessArchiveEntry(val kind: String, val mode: Int, val size: Long? = null, val sha256: String? = null, val target: String? = null)
