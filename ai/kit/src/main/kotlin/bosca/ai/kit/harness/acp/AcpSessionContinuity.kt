package bosca.ai.kit.harness.acp

/** Distinguishes native continuity from rebuilding a session using authorized canonical context. */
enum class AcpSessionContinuity {
    NEW,
    RESUMED,
    LOADED,
    RECONSTRUCTED,
}
