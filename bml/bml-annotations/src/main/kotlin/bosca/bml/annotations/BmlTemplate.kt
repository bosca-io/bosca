package bosca.bml.annotations

/**
 * Marks a generated template (a layout/fragment with slots).
 *
 * @param name the template name referenced by `layout="…"` or slot composition.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class BmlTemplate(val name: String)
