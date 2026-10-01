package bosca.analytics

/** Marks a Compose page for automatic tracking when Navigation does not own its destination. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class AnalyticsScreen(
    val id: String = "",
    val path: String = "",
    val title: String = "",
)
