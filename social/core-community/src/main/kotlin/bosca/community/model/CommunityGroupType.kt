package bosca.community.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@Serializable
@DbMapper(CommunityGroupTypeMapper::class)
enum class CommunityGroupType {
    FAMILY,
    SMALL_GROUP,
    CUSTOM
}

object CommunityGroupTypeMapper : EnumMapper<CommunityGroupType>({ CommunityGroupType.valueOf(it.uppercase()) })
