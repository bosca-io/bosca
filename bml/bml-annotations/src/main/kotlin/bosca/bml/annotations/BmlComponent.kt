package bosca.bml.annotations

/**
 * Marks a generated component — a custom tag or an override of a built-in tag.
 *
 * @param tag the tag name this component registers (e.g. `card`, or `a` to override).
 * @param overrides true when `tag` replaces a built-in tag's default handler.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class BmlComponent(val tag: String, val overrides: Boolean = false)
