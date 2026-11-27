package bosca.workops.ui

import kotlinx.serialization.Serializable

/** An authored context label and its independently authorized reference presentation. */
@Serializable
data class WorkOpsSpecContextRow(
    val id: String,
    val kind: String,
    val title: String,
    val href: String,
    val created: String,
)
