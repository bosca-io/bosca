package bosca.bml.annotations

/** Marks a compiler-generated BML message template object. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class BmlMessage(val key: String)
