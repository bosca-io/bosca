package bosca.attributes

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(AttributeTypeMapper::class)
@Serializable
enum class AttributeType {
    COLLECTION,
    DATE,
    DATE_TIME,
    DATETIME,
    FLOAT,
    INT,
    METADATA,
    PROFILE,
    STRING
}

object AttributeTypeMapper : EnumMapper<AttributeType>({ AttributeType.valueOf(it.uppercase()) })

//@WritingConverter
//class AttributeTypeToStringConverter : Converter<AttributeType, String> {
//    override fun convert(type: AttributeType): String {
//        if (type == AttributeType.DATE_TIME) return "datetime"
//        return type.name.lowercase()
//    }
//}
//
//@ReadingConverter
//class StringToAttributeTypeConverter : Converter<String, AttributeType> {
//    override fun convert(type: String): AttributeType {
//        if (type == "datetime") return AttributeType.DATE_TIME
//        return AttributeType.valueOf(type.uppercase())
//    }
//}