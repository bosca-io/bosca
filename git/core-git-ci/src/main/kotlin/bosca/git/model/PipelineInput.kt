package bosca.git.model

import kotlinx.serialization.Serializable

/** A trigger input declaration together with its name in the pipeline definition. */
data class PipelineInputDefinition(
    val name: String,
    val declaration: TriggerInput,
)

/** The supported value types of a declared pipeline input. */
enum class PipelineInputType {
    STRING,
    BOOLEAN,
    NUMBER,
    CHOICE,
}

/** A submitted trigger input; its text is validated against the declaration at run creation. */
@Serializable
data class PipelineInputValue(
    val name: String,
    val value: String,
)
