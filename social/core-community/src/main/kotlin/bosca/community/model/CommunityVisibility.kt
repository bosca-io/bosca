package bosca.community.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(CommunityVisibilityMapper::class)
@Serializable
enum class CommunityVisibility {
    PUBLIC,
    PRIVATE,
    HIDDEN
}

object CommunityVisibilityMapper : EnumMapper<CommunityVisibility>({ CommunityVisibility.valueOf(it.uppercase()) })
