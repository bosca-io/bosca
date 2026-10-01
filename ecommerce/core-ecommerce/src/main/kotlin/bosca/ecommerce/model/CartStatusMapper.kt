package bosca.ecommerce.model

import bosca.db.Mapper
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlin.reflect.KClass

/**
 * Maps the [CartStatus] value class to/from an `int` column (the bitmask). Referenced by
 * `@DbMapper(CartStatusMapper::class)` on [CartStatus]; the repository generator prefers this over
 * the auto-jsonb path, so a [CartStatus] field on an `int` column persists as the raw mask.
 */
object CartStatusMapper : Mapper<CartStatus> {

    override fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, index: Int): CartStatus? {
        val value = result.getInt(index)
        return if (result.wasNull()) null else CartStatus(value)
    }

    override fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, name: String): CartStatus? {
        val value = result.getInt(name)
        return if (result.wasNull()) null else CartStatus(value)
    }

    override fun bind(
        type: KClass<*>,
        arguments: List<KClass<*>>,
        stmt: PreparedStatement,
        index: Int,
        value: CartStatus?,
    ): PreparedStatement {
        if (value == null) {
            stmt.setNull(index, Types.INTEGER)
        } else {
            stmt.setInt(index, value.mask)
        }
        return stmt
    }
}
