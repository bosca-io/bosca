package bosca.db.mapper

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.postgresql.util.PGobject
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * Maps `@Serializable` Kotlin types to and from PostgreSQL JSONB columns.
 *
 * A single instance is created on [DefaultMappers] and reused for all `@Serializable` types.
 * The type-specific [KSerializer] is passed to each [map] and [bind] call.
 *
 * @param json the configured [Json] instance for encoding and decoding
 */
class SerializableMapper(private val json: Json) {

    /**
     * Reads a JSONB column value from the [result] set at the given column [index]
     * and deserializes it using the provided [serializer].
     */
    fun <T> map(serializer: KSerializer<T>, result: ResultSet, index: Int): T? {
        return result.getString(index)?.let { value ->
            json.decodeFromString(serializer, value)
        }
    }

    /**
     * Reads a JSONB column value from the [result] set by column [name]
     * and deserializes it using the provided [serializer].
     */
    fun <T> map(serializer: KSerializer<T>, result: ResultSet, name: String): T? {
        return result.getString(name)?.let { value ->
            json.decodeFromString(serializer, value)
        }
    }

    /**
     * Serializes the [value] using the provided [serializer] and binds it
     * to the [stmt] at the given parameter [index] as a PostgreSQL JSONB value.
     */
    fun <T> bind(serializer: KSerializer<T>, stmt: PreparedStatement, index: Int, value: T?): PreparedStatement {
        if (value == null) {
            stmt.setNull(index, Types.OTHER)
        } else {
            val jsonbObject = PGobject()
            jsonbObject.setType("jsonb")
            jsonbObject.setValue(json.encodeToString(serializer, value))
            stmt.setObject(index, jsonbObject)
        }
        return stmt
    }
}
