package bosca.graphql.annotations

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TypeController(
    val type: String = ""
)