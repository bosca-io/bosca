package bosca.security.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(GroupTypeMapper::class)
@Serializable
enum class GroupType {
    PRINCIPAL,
    SYSTEM
}

object GroupTypeMapper : EnumMapper<GroupType>({ GroupType.valueOf(it.uppercase()) })
