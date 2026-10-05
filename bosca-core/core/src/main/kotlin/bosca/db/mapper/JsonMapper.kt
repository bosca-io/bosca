package bosca.db.mapper

import bosca.db.Mapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.postgresql.util.PGobject
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlin.reflect.KClass


class JsonMapper(private val json: Json) : Mapper<JsonElement> {

    override fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, index: Int): JsonElement? {
        return result.getString(index)?.let { value ->
            json.parseToJsonElement(value)
        }
    }

    override fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, name: String): JsonElement? {
        return result.getString(name)?.let { value ->
            json.parseToJsonElement(value)
        }
    }

    override fun bind(type: KClass<*>, arguments: List<KClass<*>>, stmt: PreparedStatement, index: Int, value: JsonElement?): PreparedStatement {
        if (value == null) {
            stmt.setNull(index, Types.OTHER)
        } else {
            val jsonbObject = PGobject()
            jsonbObject.setType("jsonb")
            jsonbObject.setValue(json.encodeToString(value))
            stmt.setObject(index, jsonbObject)
        }
        return stmt
    }
}