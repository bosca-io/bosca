package bosca.graphql.annotations

import kotlin.reflect.KClass

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class BatchKey(
    val property: String = "",
    val type: KClass<*> = Unit::class
)
