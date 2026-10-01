package bosca.graphql.annotations

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Field(
    val type: String = "",
    val name: String = ""
)