package bosca.ecommerce.model

import bosca.db.Mapper
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlin.reflect.KClass

/**
 * Maps [Money] to/from `numeric` columns as a [java.math.BigDecimal]. Referenced from
 * `@DbMapper(MoneyMapper::class)` on [Money]; the repository code generator prefers this over the
 * `@Serializable` auto-jsonb path, so `Money` fields bound to `numeric` columns persist correctly.
 *
 * The [Money] constructor normalizes the read [java.math.BigDecimal] to scale 4, so column scale
 * differences are absorbed.
 */
object MoneyMapper : Mapper<Money> {

    override fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, index: Int): Money? =
        result.getBigDecimal(index)?.let { Money(it) }

    override fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, name: String): Money? =
        result.getBigDecimal(name)?.let { Money(it) }

    override fun bind(
        type: KClass<*>,
        arguments: List<KClass<*>>,
        stmt: PreparedStatement,
        index: Int,
        value: Money?,
    ): PreparedStatement {
        if (value == null) {
            stmt.setNull(index, Types.NUMERIC)
        } else {
            stmt.setBigDecimal(index, value.amount)
        }
        return stmt
    }
}
