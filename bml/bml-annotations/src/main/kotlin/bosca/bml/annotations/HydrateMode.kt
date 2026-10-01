package bosca.bml.annotations

/**
 * When an island's client module mounts onto its server-rendered DOM
 * (the `hydrate` attribute of `<island>`).
 */
enum class HydrateMode {
    /** Mount as soon as the runtime loads (default). */
    Load,

    /** Mount when the main thread is idle. */
    Idle,

    /** Mount when the island scrolls into view. */
    Visible,
}
