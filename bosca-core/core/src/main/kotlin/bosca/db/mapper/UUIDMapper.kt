package bosca.db.mapper

import bosca.db.Mapper
import bosca.serialization.UUID
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.reflect.KClass
import kotlin.uuid.toJavaUuid

class UUIDMapper : Mapper<UUID> {

    override fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, index: Int): UUID? {
        val r = result.getString(index) ?: return null
        return UUID.parse(r)
    }

    override fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, name: String): UUID? {
        val r = result.getString(name) ?: return null
        return UUID.parse(r)
    }

    override fun bind(type: KClass<*>, arguments: List<KClass<*>>, stmt: PreparedStatement, index: Int, value: UUID?): PreparedStatement {
        stmt.setObject(index, value?.toJavaUuid())
        return stmt
    }
}