package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class SystemMuxDraft(
    val tokenId: String = "", val tokenSecret: String = "",
    val clearTokenId: Boolean = false, val clearTokenSecret: Boolean = false,
    val playbackPolicy: String = "public", val maxWaitSeconds: String = "3600",
    val videoQuality: String = "default", val maxResolution: String = "default",
    val mp4Support: Boolean = true, val subtitleLanguage: String = "en", val test: Boolean = false,
)
