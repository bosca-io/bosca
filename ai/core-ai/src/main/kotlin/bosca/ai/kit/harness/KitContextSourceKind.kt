package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Explicit source kinds available to the bounded context handoff. */
@Serializable
enum class KitContextSourceKind { METADATA, COLLECTION, TASK, SPEC, REQUIREMENT, GIT_FILE }
