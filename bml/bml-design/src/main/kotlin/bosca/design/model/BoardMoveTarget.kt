package bosca.design.model

import kotlinx.serialization.Serializable

/** An application-approved destination for a neutral board card movement request. */
@Serializable
data class BoardMoveTarget(val id: String, val label: String)
