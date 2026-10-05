package bosca.db

import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.reflect.KClass

/**
 * Converts between database column values and Kotlin types.
 *
 * Implementations handle reading values from a [ResultSet] and binding values to a
 * [PreparedStatement] for a specific type [T]. The repository code generation (KSP)
 * uses registered mappers to serialize/deserialize custom types during query execution.
 *
 * @param T the Kotlin type this mapper handles
 */
interface Mapper<T> {

    /**
     * Reads a value of type [T] from the [result] set at the given column [index].
     *
     * @param type the target KClass to map to
     * @param arguments generic type arguments, if applicable
     * @param result the JDBC result set to read from
     * @param index the 1-based column index
     * @return the mapped value, or `null` if the column value is SQL NULL
     */
    fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, index: Int): T?

    /**
     * Reads a value of type [T] from the [result] set by column [name].
     *
     * @param type the target KClass to map to
     * @param arguments generic type arguments, if applicable
     * @param result the JDBC result set to read from
     * @param name the column name
     * @return the mapped value, or `null` if the column value is SQL NULL
     */
    fun map(type: KClass<*>, arguments: List<KClass<*>>, result: ResultSet, name: String): T?

    /**
     * Binds a [value] of type [T] to the [stmt] at the given parameter [index].
     *
     * @param type the KClass of the value being bound
     * @param arguments generic type arguments, if applicable
     * @param stmt the prepared statement to bind the value to
     * @param index the 1-based parameter index
     * @param value the value to bind, or `null` for SQL NULL
     * @return the same [stmt] for chaining
     */
    fun bind(type: KClass<*>, arguments: List<KClass<*>>, stmt: PreparedStatement, index: Int, value: T?): PreparedStatement
}

