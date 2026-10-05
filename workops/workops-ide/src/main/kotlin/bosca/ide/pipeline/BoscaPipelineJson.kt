package bosca.ide.pipeline

import com.google.gson.JsonObject

/** Strict mapping at the Bosca GraphQL boundary; entity identity always includes the originating server. */
internal object BoscaPipelineJson {
    fun parsePipeline(serverId: String, repositoryId: String, json: JsonObject) = BoscaPipeline(
        serverProfileId = serverId,
        repositoryId = repositoryId,
        id = json.string("id"),
        name = json.string("name"),
        filePath = json.string("filePath"),
        triggerTypes = json.getAsJsonArray("triggerTypes").map { it.asString },
        runs = json.getAsJsonArray("runs").map { parseRun(it.asJsonObject) },
    )

    fun parseLogLine(json: JsonObject) = BoscaPipelineLogLine(
        lineNumber = json.get("lineNumber").asInt,
        timestamp = json.string("timestamp"),
        content = json.string("content"),
        stream = json.string("stream"),
    )

    private fun parseRun(json: JsonObject) = BoscaPipelineRun(
        id = json.string("id"),
        number = json.get("number").asInt,
        status = json.string("status"),
        ref = json.string("ref"),
        commitSha = json.string("commitSha"),
        triggerType = json.string("triggerType"),
        created = json.string("created"),
        started = json.nullableString("started"),
        finished = json.nullableString("finished"),
        durationSeconds = json.get("durationSeconds")?.takeUnless { it.isJsonNull }?.asLong,
        jobs = json.getAsJsonArray("jobs").map { parseJob(it.asJsonObject) },
        artifacts = json.getAsJsonArray("artifacts").map { artifact ->
            val value = artifact.asJsonObject
            BoscaPipelineArtifact(
                id = value.string("id"),
                name = value.string("name"),
                sizeBytes = value.get("sizeBytes").asLong,
                created = value.string("created"),
            )
        },
    )

    private fun parseJob(json: JsonObject) = BoscaPipelineJob(
        id = json.string("id"),
        name = json.string("name"),
        status = json.string("status"),
        runnerLabel = json.string("runnerLabel"),
        attempt = json.get("attempt").asInt,
        awaitingRequirements = json.get("awaitingRequirements").asBoolean,
        awaitingApproval = json.get("awaitingApproval").asBoolean,
        approvalRequired = json.get("approvalRequired").asBoolean,
        errorMessage = json.nullableString("errorMessage"),
        started = json.nullableString("started"),
        finished = json.nullableString("finished"),
        steps = json.getAsJsonArray("steps").map { parseStep(it.asJsonObject) },
    )

    private fun parseStep(json: JsonObject) = BoscaPipelineStep(
        id = json.string("id"),
        name = json.string("name"),
        ordinal = json.get("ordinal").asInt,
        status = json.string("status"),
        exitCode = json.get("exitCode")?.takeUnless { it.isJsonNull }?.asInt,
        errorMessage = json.nullableString("errorMessage"),
        started = json.nullableString("started"),
        finished = json.nullableString("finished"),
    )

    private fun JsonObject.string(name: String): String = get(name).asString
    private fun JsonObject.nullableString(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
}
