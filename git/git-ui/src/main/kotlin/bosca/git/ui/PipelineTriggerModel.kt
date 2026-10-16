package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json

@Serializable
class PipelineTriggerModel(
    var page: PipelinePage,
    var values: List<GitPipelineInputValueInput> = emptyList(),
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
    var editing: Boolean = false,
) {
    fun value(name: String, default: String?): String =
        values.firstOrNull { it.name == name }?.value ?: default.orEmpty()

    suspend fun selectRef(ref: String, inputs: String, ctx: RenderContext) {
        editing = true
        failed = false
        message = ""
        try {
            val retained = Json.decodeFromString<List<GitPipelineInputValueInput>>(inputs)
            require(ref in page.refs || (ref.startsWith("refs/tags/") &&
                ctx.gql.execute(GitUiTag, GitUiTag.Variables(page.repository, ref.removePrefix("refs/tags/"))).git.tag != null)) {
                "Choose an available branch or tag."
            }
            val definitions =
                ctx.gql
                    .execute(GitUiPipelineInputs, GitUiPipelineInputs.Variables(page.selected, ref))
                    .git
                    .pipelineInputs
            values = retained.filter { value ->
                definitions.any {
                    it.name == value.name &&
                        (it.type != GitPipelineInputType.CHOICE || value.value in it.options)
                }
            }
            page = page.copy(ref = ref, inputs = definitions, refs = (page.refs + ref).distinct())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Could not load pipeline inputs."
        }
    }

    suspend fun start(inputs: String, ctx: RenderContext) {
        failed = false
        message = ""
        try {
            values = Json.decodeFromString(inputs)
            require(page.canExecute) { "Execute permission is required." }
            val definitions = coroutineScope {
                val pipelineRequest = async {
                    ctx.gql.execute(GitUiPipelineTriggers, GitUiPipelineTriggers.Variables(page.repository))
                        .git.pipelines.firstOrNull { it.id == page.selected } ?: error("Pipeline no longer exists.")
                }
                val inputsRequest = async {
                    ctx.gql.execute(GitUiPipelineInputs, GitUiPipelineInputs.Variables(page.selected, page.ref))
                        .git.pipelineInputs
                }
                require(GitPipelineTriggerType.MANUAL in pipelineRequest.await().triggerTypes) {
                    "This pipeline has no manual trigger."
                }
                inputsRequest.await()
            }
            require(
                values.map { it.name }.distinct().size == values.size &&
                    values.all { value -> definitions.any { it.name == value.name } }
            ) {
                "Input definitions changed. Reload the form."
            }
            for (input in definitions) {
                val value =
                    values.firstOrNull { it.name == input.name }?.value
                        ?: input.defaultValue.orEmpty()
                require(!input.required || value.isNotBlank()) { "${input.name} is required." }
                if (value.isBlank()) continue
                when (input.type) {
                    GitPipelineInputType.BOOLEAN ->
                        require(value == "true" || value == "false") {
                            "${input.name} must be true or false."
                        }
                    GitPipelineInputType.NUMBER ->
                        require(value.toDoubleOrNull()?.isFinite() == true) {
                            "${input.name} must be a number."
                        }
                    GitPipelineInputType.CHOICE ->
                        require(value in input.options) { "Choose a valid ${input.name}." }
                    GitPipelineInputType.STRING -> Unit
                }
            }
            nextHref =
                runHref(
                    ctx.gql
                        .execute(
                            GitUiTrigger,
                            GitUiTrigger.Variables(page.selected, page.ref, values),
                        )
                        .git
                        .triggerPipeline
                        .id
                )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Could not start pipeline."
        }
    }
}
