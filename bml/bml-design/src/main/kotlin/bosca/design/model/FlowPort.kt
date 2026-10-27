package bosca.design.model

import kotlinx.serialization.Serializable

/** A named card connection with an application-owned identifier. */
@Serializable
data class FlowPort(val id: String, val label: String? = null)
