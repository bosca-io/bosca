package bosca.cli.harness

import kotlinx.serialization.Serializable

/** Private generation-local launch settings, outside retained state. */
@Serializable
internal data class HarnessMcpProcess(val command: List<String>, val environment: Map<String, String>)
