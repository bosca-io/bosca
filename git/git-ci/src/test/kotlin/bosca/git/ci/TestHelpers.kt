package bosca.git.ci

import bosca.git.model.PipelineConcurrency
import bosca.git.model.PipelineTrigger
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val testJson = Json { ignoreUnknownKeys = true }

fun List<PipelineTrigger>.toJsonElement(): JsonElement =
    testJson.encodeToJsonElement(ListSerializer(PipelineTrigger.serializer()), this)

fun PipelineConcurrency.toJsonElement(): JsonElement =
    testJson.encodeToJsonElement(PipelineConcurrency.serializer(), this)
