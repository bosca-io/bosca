package bosca.profile.profile.graphql

import bosca.profile.model.Profile
import kotlinx.serialization.Serializable

@Serializable
class ProfileChat(val profile: Profile)
