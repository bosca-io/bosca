package bosca.analytics

/** Supplies stable identity and element metadata to compiler-detected instrumentation. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.BINARY)
annotation class AutoInstrument(
    val id: String = "",
    val elementType: String = "function",
    val trackVisibility: Boolean = false,
    val visibilityThreshold: Float = 0.5f,
    val visibilityDwellMillis: Long = 1_000,
)

/** Excludes a class or member from automatic instrumentation. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.BINARY)
annotation class AnalyticsIgnore
