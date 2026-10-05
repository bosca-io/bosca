package bosca.bml.annotations

/**
 * Marks a generated client↔server contract dispatcher. The K2 plugin emits a
 * server route and a typed TypeScript client stub from the author's `<contract>`
 * interface. The contract implementation accesses data only via the
 * GraphQL client.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class BmlContract
