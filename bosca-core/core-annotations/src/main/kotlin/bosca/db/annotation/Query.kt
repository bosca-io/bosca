package bosca.db.annotation

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Query(
    val value: String,
    val returnUpdateCount: Boolean = false,
    val autoCloseResult: Boolean = true,
    val autoCloseStatement: Boolean = true
)
