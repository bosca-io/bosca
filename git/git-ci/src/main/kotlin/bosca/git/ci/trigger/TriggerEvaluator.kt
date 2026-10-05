package bosca.git.ci.trigger

import bosca.git.model.Pipeline
import bosca.git.model.PipelineTrigger
import bosca.git.model.PipelineTriggerType
import bosca.git.model.PushEvent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Evaluates whether a pipeline's triggers match a given event.
 * Supports branch glob matching, path filtering (include/exclude),
 * and tag pattern matching.
 */
class TriggerEvaluator(private val json: Json = Json { ignoreUnknownKeys = true }) {

    private fun deserializeTriggers(pipeline: Pipeline): List<PipelineTrigger> {
        return json.decodeFromJsonElement(ListSerializer(PipelineTrigger.serializer()), pipeline.triggers)
    }

    fun evaluatePush(pipeline: Pipeline, event: PushEvent, changedPaths: List<String> = emptyList()): PipelineTrigger? {
        return deserializeTriggers(pipeline)
            .filter { it.type == PipelineTriggerType.PUSH }
            .firstOrNull { trigger -> matchesPushTrigger(trigger, event, changedPaths) }
    }

    fun evaluateTag(pipeline: Pipeline, tagName: String): PipelineTrigger? {
        return deserializeTriggers(pipeline)
            .filter { it.type == PipelineTriggerType.TAG }
            .firstOrNull { trigger ->
                trigger.tags.isEmpty() || trigger.tags.any { matchesGlob(it, tagName) }
            }
    }

    fun supportsManualTrigger(pipeline: Pipeline): Boolean {
        return deserializeTriggers(pipeline).any { it.type == PipelineTriggerType.MANUAL }
    }

    private fun matchesPushTrigger(trigger: PipelineTrigger, event: PushEvent, changedPaths: List<String>): Boolean {
        val branchName = extractBranchName(event.ref) ?: return false

        if (trigger.branches.isNotEmpty() && !trigger.branches.any { matchesGlob(it, branchName) }) {
            return false
        }

        if (changedPaths.isNotEmpty()) {
            if (trigger.pathsIgnore.isNotEmpty() && changedPaths.all { path ->
                    trigger.pathsIgnore.any { matchesGlob(it, path) }
                }) {
                return false
            }

            if (trigger.paths.isNotEmpty() && !changedPaths.any { path ->
                    trigger.paths.any { matchesGlob(it, path) }
                }) {
                return false
            }
        }

        return true
    }

    private fun extractBranchName(ref: String): String? {
        return when {
            ref.startsWith("refs/heads/") -> ref.removePrefix("refs/heads/")
            ref.startsWith("refs/tags/") -> null
            else -> ref
        }
    }

    companion object {

        /**
         * Matches a glob pattern against a value. Supports:
         * - `*` matches any characters except `/`
         * - `**` matches any characters including `/`
         * - `?` matches a single character
         */
        fun matchesGlob(pattern: String, value: String): Boolean {
            val regex = buildString {
                append("^")
                var i = 0
                while (i < pattern.length) {
                    when {
                        i + 1 < pattern.length && pattern[i] == '*' && pattern[i + 1] == '*' -> {
                            append(".*")
                            i += 2
                            if (i < pattern.length && pattern[i] == '/') i++
                        }
                        pattern[i] == '*' -> {
                            append("[^/]*")
                            i++
                        }
                        pattern[i] == '?' -> {
                            append("[^/]")
                            i++
                        }
                        else -> {
                            append(Regex.escape(pattern[i].toString()))
                            i++
                        }
                    }
                }
                append("$")
            }
            return Regex(regex).matches(value)
        }
    }
}
