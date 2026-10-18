package bosca.bml.codegen

/** Compile-time capabilities propagated through local and imported component calls. */
internal data class BmlComponentCapabilities(
    val deferred: Set<String>,
    val clientBehavior: Set<String>,
    val session: Set<String>,
    val featureFlags: Set<String>,
)
