package bosca.content.metadata.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(DataTypeMapper::class)
@Serializable
enum class DataType {
    ATTRIBUTES,
    TABLE
}

object DataTypeMapper : EnumMapper<DataType>({ DataType.valueOf(it.uppercase()) })
