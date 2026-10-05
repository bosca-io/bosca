package bosca.attributes

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(AttributeLocationMapper::class)
@Serializable
enum class AttributeLocation {
    ITEM,
    RELATIONSHIP
}

object AttributeLocationMapper : EnumMapper<AttributeLocation>({ AttributeLocation.valueOf(it.uppercase()) })