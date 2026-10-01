package bosca.bml.annotations

/**
 * Marks a declaration emitted by the BML compiler from a `.bml` source file.
 *
 * The frontend records the originating source path so tooling, diagnostics, and
 * source maps can map generated `.kt` back to `.bml`.
 *
 * @param source the originating `.bml` file path, relative to the project root.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FILE)
@Retention(AnnotationRetention.BINARY)
annotation class BmlGenerated(val source: String)
