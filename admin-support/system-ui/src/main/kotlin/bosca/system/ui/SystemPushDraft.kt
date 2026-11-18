package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class SystemPushDraft(
    val enabled: Boolean = false, val fcmEnabled: Boolean = false,
    val serviceAccountJson: String = "null", val useDeploymentCredentials: Boolean = false,
    val apnsEnabled: Boolean = false, val teamId: String = "", val keyId: String = "", val bundleId: String = "",
    val privateKey: String = "", val clearPrivateKey: Boolean = false, val sandbox: Boolean = true,
)
