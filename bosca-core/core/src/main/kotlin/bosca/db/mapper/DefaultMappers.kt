package bosca.db.mapper

import bosca.serialization.LocalDateTime
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.json.Json

class DefaultMappers(json: Json) {

    val int = TypeMapper(Int::class)
    val long = TypeMapper(Long::class)
    val float = TypeMapper(Float::class)
    val double = TypeMapper(Double::class)
    val string = TypeMapper(String::class)
    val boolean = TypeMapper(Boolean::class)
    val uuid = UUIDMapper()
    val array = TypeMapper(Array::class)
    val byteArray = TypeMapper(ByteArray::class)
    val offsetDateTime = TypeMapper(OffsetDateTime::class)
    val localDateTime = TypeMapper(LocalDateTime::class)
    val instant = TypeMapper(kotlin.time.Instant::class)
    val jsonElement = JsonMapper(json)
    val serializable = SerializableMapper(json)

    fun <T : Enum<T>> enum(type: kotlin.reflect.KClass<T>): EnumMapper<T> {
        return EnumMapper { java.lang.Enum.valueOf(type.java, it) }
    }
}