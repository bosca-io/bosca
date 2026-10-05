package bosca.storage.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(StorageSystemTypeMapper::class)
@Serializable
enum class StorageSystemType {
    SEARCH,
    SUPPLEMENTARY,
    VECTOR,
}

object StorageSystemTypeMapper : EnumMapper<StorageSystemType>({ StorageSystemType.valueOf(it.uppercase()) })