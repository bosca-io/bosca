package bosca.bml.annotations

/**
 * Marks a generated island boundary. The runtime mounts the matching client
 * module onto the server-rendered DOM, isolating each instance.
 *
 * @param name the island name within its page/component.
 * @param hydrate when the client module mounts.
 * @param render whether the island's server HTML is produced per request or
 *   pre-rendered at build time (it hydrates the same either way).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class BmlIsland(
    val name: String,
    val hydrate: HydrateMode = HydrateMode.Load,
    val render: RenderMode = RenderMode.Request,
)
