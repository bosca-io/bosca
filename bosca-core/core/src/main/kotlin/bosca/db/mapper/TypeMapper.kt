package bosca.db.mapper

import bosca.db.Mapper
import bosca.serialization.LocalDateTime
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Time
import java.sql.Timestamp
import java.sql.Types
import java.util.*
import kotlin.reflect.KClass


class TypeMapper<T : Any>(private val type: KClass<T>) : Mapper<T> {

    private val javaType = type.javaObjectType

    @Suppress("UNCHECKED_CAST")
    override fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, index: Int): T? {
        if (type == OffsetDateTime::class) {
            return result.getObject(index, OffsetDateTime::class.java) as T?
        }
        if (type == ByteArray::class) return result.getBytes(index) as T?
        if (type == Array::class || (type != ByteArray::class && type.javaObjectType.isArray)) {
            val r = result.getArray(index) ?: return null
            return mapArray(r, arguments) as T?
        }
        val obj = result.getObject(index) ?: return null
        return coerceNumeric(obj) ?: obj as T?
    }

    @Suppress("UNCHECKED_CAST")
    override fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, name: String): T? {
        if (type == ByteArray::class) return result.getBytes(name) as T?
        if (type.javaObjectType.isArray) {
            val r = result.getArray(name) ?: return null
            return mapArray(r, arguments) as T?
        }
        if (type == OffsetDateTime::class) {
            return result.getObject(name, OffsetDateTime::class.java) as T?
        }
        if (type == LocalDateTime::class) {
            return result.getObject(name, LocalDateTime::class.java) as T?
        }
        val obj = result.getObject(name) ?: return null
        return coerceNumeric(obj) ?: obj as T?
    }

    private fun mapArray(sqlArray: java.sql.Array, arguments: List<KClass<*>>): Any? {
        val raw = sqlArray.array
        if (arguments.isNotEmpty()) {
            val element = arguments.first()
            if (element.java.isEnum) {
                @Suppress("UNCHECKED_CAST")
                val enumClass = element.java as Class<out Enum<*>>
                val constants = enumClass.enumConstants
                val strings = raw as Array<*>
                return strings.map { value ->
                    constants.firstOrNull { it.name.equals(value.toString(), ignoreCase = true) }
                        ?: error("No enum constant of ${enumClass.name} matches '$value'")
                }.toTypedArray()
            }
            // The Postgres `uuid[]` driver-side decode produces a
            // `java.util.UUID[]`. Bosca's typealias `bosca.serialization.UUID`
            // resolves to `kotlin.uuid.Uuid`, so without explicit conversion
            // a `List<UUID>` field deserialized from a `uuid[]` column
            // surfaces as `java.util.UUID` instances under Kotlin generics
            // erasure — every Kotlin-side `equals`, `in`, or set-membership
            // check against another `kotlin.uuid.Uuid` then mis-compares.
            // Convert eagerly so consumers get the type the model declares.
            if (element == UUID::class) {
                val raws = raw as Array<*>
                return raws.map { value ->
                    when (value) {
                        is java.util.UUID -> kotlin.uuid.Uuid.fromLongs(
                            value.mostSignificantBits,
                            value.leastSignificantBits,
                        )
                        else -> value
                    }
                }.toTypedArray()
            }
        }
        return raw
    }

    @Suppress("UNCHECKED_CAST")
    private fun coerceNumeric(obj: Any): T? {
        if (obj !is Number) return null
        return when (type) {
            Float::class -> obj.toFloat()
            Double::class -> obj.toDouble()
            Int::class -> obj.toInt()
            Long::class -> obj.toLong()
            Short::class -> obj.toShort()
            Byte::class -> obj.toByte()
            else -> null
        } as T?
    }

    override fun bind(type: KClass<*>, arguments: List<KClass<*>>, stmt: PreparedStatement, index: Int, value: T?): PreparedStatement {
        if (value == null) {
            stmt.setNull(index, getSqlType(javaType))
        } else if (type == Array::class && arguments.isNotEmpty()) {
            bindArray(arguments.first(), stmt, index, value)
        } else {
            stmt.setObject(index, value)
        }
        return stmt
    }

    private fun bindArray(elementType: KClass<*>, stmt: PreparedStatement, index: Int, value: T) {
        when {
            elementType == UUID::class -> {
                @Suppress("UNCHECKED_CAST")
                // The Postgres JDBC driver's `createArrayOf("uuid", …)`
                // accepts strings or `java.util.UUID` but not Kotlin's
                // `kotlin.uuid.Uuid`. Convert here so callers can hand
                // us the typealias without surfacing the JDBC driver's
                // expectation.
                val converted = (value as Array<*>).map { item ->
                    when (item) {
                        is kotlin.uuid.Uuid -> java.util.UUID.fromString(item.toString())
                        else -> item
                    }
                }.toTypedArray()
                val array = stmt.connection.createArrayOf("uuid", converted)
                stmt.setArray(index, array)
            }
            elementType.java.isEnum -> {
                @Suppress("UNCHECKED_CAST")
                val stringArray = (value as Array<Enum<*>>).map { it.name.lowercase() }.toTypedArray()
                val array = stmt.connection.createArrayOf("varchar", stringArray)
                stmt.setArray(index, array)
            }
            elementType == String::class -> {
                @Suppress("UNCHECKED_CAST")
                val array = stmt.connection.createArrayOf("varchar", value as Array<String>)
                stmt.setArray(index, array)
            }
            elementType == Int::class -> {
                @Suppress("UNCHECKED_CAST")
                val array = stmt.connection.createArrayOf("integer", value as Array<Int>)
                stmt.setArray(index, array)
            }
            elementType == Long::class -> {
                @Suppress("UNCHECKED_CAST")
                val array = stmt.connection.createArrayOf("bigint", value as Array<Long>)
                stmt.setArray(index, array)
            }
            else -> stmt.setObject(index, value)
        }
    }

    private fun getSqlType(javaType: Class<*>?): Int {
        if (javaType == null) {
            return Types.NULL
        }
        if (javaType == String::class.javaObjectType) {
            return Types.VARCHAR
        } else if (javaType == Int::class.javaObjectType || javaType == Int::class.javaPrimitiveType) {
            return Types.INTEGER
        } else if (javaType == Long::class.javaObjectType || javaType == Long::class.javaPrimitiveType) {
            return Types.BIGINT
        } else if (javaType == Boolean::class.javaObjectType || javaType == Boolean::class.javaPrimitiveType) {
            return Types.BOOLEAN
        } else if (javaType == Double::class.javaObjectType || javaType == Double::class.javaPrimitiveType) {
            return Types.DOUBLE
        } else if (javaType == Float::class.javaObjectType || javaType == Float::class.javaPrimitiveType) {
            return Types.FLOAT
        } else if (javaType == Short::class.javaObjectType || javaType == Short::class.javaPrimitiveType) {
            return Types.SMALLINT
        } else if (javaType == Byte::class.javaObjectType || javaType == Byte::class.javaPrimitiveType) {
            return Types.TINYINT
        } else if (javaType == BigDecimal::class.javaObjectType) {
            return Types.DECIMAL
        } else if (javaType == Date::class.javaObjectType) {
            return Types.DATE
        } else if (javaType == Time::class.javaObjectType) {
            return Types.TIME
        } else if (javaType == Timestamp::class.javaObjectType) {
            return Types.TIMESTAMP
        } else if (javaType.isArray && javaType.componentType == Byte::class.javaPrimitiveType) {
            return Types.VARBINARY
        }
        return Types.OTHER
    }
}