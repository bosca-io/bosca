package bosca.ai.kit.harness

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Kind of a resolved source. Lowercase JSON labels preserve stored context and its revision digest. */
@Serializable
enum class KitContextReferenceKind {
    @SerialName("metadata")
    METADATA,
    @SerialName("collection")
    COLLECTION,
    @SerialName("memory")
    MEMORY,
    @SerialName("task")
    TASK,
    @SerialName("spec")
    SPEC,
    @SerialName("requirement")
    REQUIREMENT,
    @SerialName("git_file")
    GIT_FILE,
}
