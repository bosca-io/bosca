package bosca.db.annotation

import kotlin.reflect.KClass

/**
 * Binds a [bosca.db.Mapper] to a column.
 *
 * Two placements are supported:
 *  - **On a type** (`CLASS`) — every repository column of that type uses the mapper (e.g. `Money`,
 *    native-enum types). The mapper is a `Mapper<T>` resolved by `KClass`.
 *  - **On a model property** (`PROPERTY`/`FIELD`/`VALUE_PARAMETER`) — only that one column uses the
 *    mapper, regardless of its declared type. This is how a strongly-typed property whose column is
 *    `jsonb` (a model, or a `List`/`Map`/`Set` of models) is bound with [bosca.db.mapper.JsonbMapper]
 *    instead of falling back to the SQL-array or auto-jsonb paths. Use the explicit `@property:`
 *    use-site so the repository generator reads it off the property declaration.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class DbMapper(val value: KClass<*>)
