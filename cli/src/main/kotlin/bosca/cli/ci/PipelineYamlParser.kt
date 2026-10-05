package bosca.cli.ci

import org.yaml.snakeyaml.Yaml

data class StepDefinition(
    val name: String,
    val uses: String? = null,
    val run: String? = null,
    val image: String? = null,
    val condition: String? = null,
    val with: Map<String, String> = emptyMap(),
    val env: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
)

/**
 * A produced-artifact declaration — registry [type] + [namespace] + a type-specific
 * [coordinate] (which supports `${{ }}` interpolation). Mirrors git-ci's `ArtifactDefinition`.
 */
data class ArtifactDefinition(
    val type: String,
    val namespace: String,
    val coordinate: String,
)

/**
 * A server-side pipeline-completion requirement retained by the local parser.
 * The local runner does not wait for remote runs; keeping the declaration makes that divergence
 * explicit instead of silently losing new server YAML fields.
 */
data class PipelineRequirementDefinition(
    val pipeline: String,
    val repository: String,
    val ref: String? = null,
    val timeoutMinutes: Int? = null,
)

/** A server-managed release environment retained for local parsing and diagnostics. */
data class EnvironmentDefinition(
    val deployOnRelease: Boolean = false,
    val promotesFrom: String? = null,
    val approval: Boolean = false,
)

data class JobDefinition(
    val runner: String = "default",
    val timeoutMinutes: Int? = null,
    val needs: List<String> = emptyList(),
    val matrix: Map<String, List<String>>? = null,
    val condition: String? = null,
    val steps: List<StepDefinition> = emptyList(),
    val artifacts: List<ArtifactDefinition> = emptyList(),
    val pipelineRequires: List<PipelineRequirementDefinition> = emptyList(),
    val environment: String? = null,
    val approval: Boolean = false,
)

data class PipelineDefinition(
    val name: String,
    val env: Map<String, String> = emptyMap(),
    val secrets: List<String> = emptyList(),
    val triggerTypes: Set<String> = emptySet(),
    val environments: Map<String, EnvironmentDefinition> = emptyMap(),
    val pipelineRequires: List<PipelineRequirementDefinition> = emptyList(),
    val jobs: Map<String, JobDefinition> = emptyMap(),
) {
    /**
     * True when this definition contains orchestration that only the Git server can enforce.
     * Local runs still execute selected job steps with event=manual.
     */
    fun hasServerOrchestration(): Boolean =
        triggerTypes.any { it == "release" || it == "promotion" } ||
            environments.isNotEmpty() ||
            pipelineRequires.isNotEmpty() ||
            jobs.values.any { it.pipelineRequires.isNotEmpty() || it.environment != null || it.approval }
}

class PipelineYamlParser {

    private val yaml = Yaml()

    fun parse(yamlContent: String): PipelineDefinition {
        val rawRoot = yaml.load<Map<Any, Any?>>(yamlContent)
            ?: throw IllegalArgumentException("Empty pipeline file")

        val root = rawRoot.mapKeys { it.key.toString() }

        val name = root["name"] as? String
            ?: throw IllegalArgumentException("Pipeline must have a 'name' field")

        return PipelineDefinition(
            name = name,
            env = parseStringMap(root["env"]),
            secrets = parseStringList(root["secrets"]),
            triggerTypes = parseTriggerTypes(rawRoot["on"] ?: rawRoot[true]),
            environments = parseEnvironments(root["environments"]),
            pipelineRequires = parsePipelineRequirements(root["requires"]),
            jobs = parseJobs(root["jobs"]),
        )
    }

    private fun parseJobs(jobs: Any?): Map<String, JobDefinition> {
        val jobsMap = jobs as? Map<*, *> ?: return emptyMap()
        return jobsMap.entries.associate { (key, value) ->
            val jobName = key.toString()
            val jobMap = value as? Map<*, *> ?: return@associate jobName to JobDefinition()
            jobName to parseJob(jobMap)
        }
    }

    private fun parseJob(map: Map<*, *>): JobDefinition {
        return JobDefinition(
            runner = map["runner"] as? String ?: "default",
            timeoutMinutes = parseTimeoutMinutes(map["timeout"]),
            needs = parseStringList(map["needs"]),
            matrix = parseMatrix(map["matrix"]),
            condition = map["if"] as? String,
            steps = parseSteps(map["steps"]),
            artifacts = parseArtifacts(map["artifacts"]),
            pipelineRequires = parsePipelineRequirements(map["requires"]),
            environment = map["environment"] as? String,
            approval = map["approval"] == true,
        )
    }

    private fun parseTriggerTypes(value: Any?): Set<String> {
        return when (value) {
            is Map<*, *> -> value.keys.mapNotNull { it as? String }.toSet()
            is List<*> -> value.mapNotNull { it as? String }.toSet()
            is String -> setOf(value)
            else -> emptySet()
        }
    }

    private fun parseEnvironments(value: Any?): Map<String, EnvironmentDefinition> {
        val map = value as? Map<*, *> ?: return emptyMap()
        return map.entries.mapNotNull { (rawKey, rawValue) ->
            val key = rawKey as? String ?: return@mapNotNull null
            val policy = rawValue as? Map<*, *>
            key to EnvironmentDefinition(
                deployOnRelease = policy?.get("deploy-on") == "release",
                promotesFrom = policy?.get("promotes-from") as? String,
                approval = policy?.get("approval") == "required" || policy?.get("approval") == true,
            )
        }.toMap()
    }

    private fun parsePipelineRequirements(value: Any?): List<PipelineRequirementDefinition> {
        val list = value as? List<*> ?: return emptyList()
        return list.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val pipeline = map["pipeline"] as? String ?: return@mapNotNull null
            val repository = map["repository"] as? String ?: return@mapNotNull null
            PipelineRequirementDefinition(
                pipeline = pipeline,
                repository = repository,
                ref = map["ref"] as? String,
                timeoutMinutes = parseTimeoutMinutes(map["timeout"]),
            )
        }
    }

    private fun parseArtifacts(artifacts: Any?): List<ArtifactDefinition> {
        val list = artifacts as? List<*> ?: return emptyList()
        return list.mapNotNull { artifact ->
            val map = artifact as? Map<*, *> ?: return@mapNotNull null
            val type = map["type"] as? String ?: return@mapNotNull null
            val namespace = map["namespace"] as? String ?: return@mapNotNull null
            val coordinate = map["coordinate"] as? String ?: return@mapNotNull null
            ArtifactDefinition(type = type, namespace = namespace, coordinate = coordinate)
        }
    }

    private fun parseSteps(steps: Any?): List<StepDefinition> {
        val stepsList = steps as? List<*> ?: return emptyList()
        return stepsList.mapNotNull { step ->
            val stepMap = step as? Map<*, *> ?: return@mapNotNull null
            StepDefinition(
                name = stepMap["name"] as? String ?: "Unnamed step",
                uses = stepMap["uses"] as? String,
                run = stepMap["run"] as? String,
                image = stepMap["image"] as? String,
                condition = stepMap["if"] as? String,
                with = parseStringMap(stepMap["with"]),
                env = parseStringMap(stepMap["env"]),
                workingDirectory = stepMap["working-directory"] as? String,
            )
        }
    }

    private fun parseMatrix(matrix: Any?): Map<String, List<String>>? {
        val matrixMap = matrix as? Map<*, *> ?: return null
        return matrixMap.entries.associate { (key, value) ->
            key.toString() to parseStringList(value)
        }
    }

    private fun parseTimeoutMinutes(value: Any?): Int? {
        val str = value?.toString() ?: return null
        return when {
            str.endsWith("h") -> str.removeSuffix("h").toDoubleOrNull()?.times(60)?.toInt()
            str.endsWith("m") -> str.removeSuffix("m").toIntOrNull()
            else -> str.toIntOrNull()
        }
    }

    private fun parseStringList(value: Any?): List<String> {
        return when (value) {
            is List<*> -> value.mapNotNull { it?.toString() }
            is String -> listOf(value)
            else -> emptyList()
        }
    }

    private fun parseStringMap(value: Any?): Map<String, String> {
        val map = value as? Map<*, *> ?: return emptyMap()
        return map.entries.associate { (k, v) ->
            k.toString() to flattenValue(v)
        }
    }

    private fun flattenValue(value: Any?): String {
        return when (value) {
            is List<*> -> value.joinToString(",") { it?.toString() ?: "" }
            is String -> value
            else -> value?.toString() ?: ""
        }
    }
}
