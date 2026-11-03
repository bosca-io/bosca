package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class SystemTokenOwner(val principalId: String, val profileId: String, val name: String)
