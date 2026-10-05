package bosca.db.mapper

import bosca.db.Mapper
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlin.reflect.KClass

open class EnumMapper<T : Enum<*>>(private val allocator: (value: String) -> T) : Mapper<T> {

    override fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, index: Int): T? {
        return result.getString(index)?.let { type ->
            return allocator(type.uppercase())
        }
    }

    override fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, name: String): T? {
        return result.getString(name)?.let { type ->
            return allocator(type.uppercase())
        }
    }

    override fun bind(type: KClass<*>, arguments: List<KClass<*>>, stmt: PreparedStatement, index: Int, value: T?): PreparedStatement {
        if (value == null) {
            stmt.setNull(index, Types.VARCHAR)
        } else {
            stmt.setString(index, value.name.lowercase())
        }
        return stmt
    }
}