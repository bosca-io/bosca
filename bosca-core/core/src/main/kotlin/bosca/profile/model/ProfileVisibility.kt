package bosca.profile.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(ProfileVisibilityMapper::class)
@Serializable
enum class ProfileVisibility {
    SYSTEM,
    USER,
    FRIENDS,
    FRIENDS_OF_FRIENDS,
    PUBLIC
}

object ProfileVisibilityMapper : EnumMapper<ProfileVisibility>({ ProfileVisibility.valueOf(it.uppercase()) })
