package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class RefOption(
    val name: String,
    val kind: String,
    val sha: String,
    val message: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
)
