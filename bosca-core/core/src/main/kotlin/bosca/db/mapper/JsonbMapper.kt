package bosca.db.mapper

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.postgresql.util.PGobject
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * Maps any kotlinx-`@Serializable` value — including collections such as `List<T>`, `Set<T>`, and
 * `Map<K, V>` — to and from a PostgreSQL `jsonb` column.
 *
 * Attach it to a repository model property whose column is `jsonb` but whose Kotlin type is a
 * strongly-typed model (or collection of models) rather than a raw
 * [kotlinx.serialization.json.JsonElement]:
 *
 * ```
 * data class Cart(
 *     @property:DbMapper(JsonbMapper::class)
 *     val items: List<CartItem>,
 *     ...
 * )
 * ```
 *
 * Unlike a [bosca.db.Mapper], the per-call [KSerializer] is supplied by the repository code
 * generator from the property's declared type (e.g. `ListSerializer(CartItem.serializer())`) — never
 * resolved reflectively — so this is safe in the GraalVM native image. The configured DI [Json] is
 * passed in per call so contextual serializers (UUID, OffsetDateTime) resolve exactly as they do
 * elsewhere. (Named for the `jsonb` column type, distinct from [JsonMapper], which reads/writes a
 * raw [kotlinx.serialization.json.JsonElement].)
 */
object JsonbMapper {

    fun <T> bind(
        json: Json,
        serializer: KSerializer<T>,
        stmt: PreparedStatement,
        index: Int,
        value: T?,
    ): PreparedStatement {
        if (value == null) {
            stmt.setNull(index, Types.OTHER)
        } else {
            val jsonb = PGobject()
            jsonb.type = "jsonb"
            jsonb.value = json.encodeToString(serializer, value)
            stmt.setObject(index, jsonb)
        }
        return stmt
    }

    fun <T> map(json: Json, serializer: KSerializer<T>, result: ResultSet, index: Int): T? =
        result.getString(index)?.let { json.decodeFromString(serializer, it) }

    fun <T> map(json: Json, serializer: KSerializer<T>, result: ResultSet, name: String): T? =
        result.getString(name)?.let { json.decodeFromString(serializer, it) }
}
