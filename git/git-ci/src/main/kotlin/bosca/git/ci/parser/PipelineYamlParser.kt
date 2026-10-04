package bosca.git.ci.parser

import bosca.git.model.ArtifactDefinition
import bosca.git.model.ArtifactRequirement
import bosca.git.model.EnvironmentDefinition
import bosca.git.model.JobDefinition
import bosca.git.model.PipelineConcurrency
import bosca.git.model.PipelineDefinition
import bosca.git.model.PipelineRequirement
import bosca.git.model.PipelineTrigger
import bosca.git.model.PipelineTriggerType
import bosca.git.model.StepDefinition
import bosca.git.model.TriggerInput
import bosca.scheduler.cron.CronExpression
import org.yaml.snakeyaml.Yaml
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Parses pipeline YAML files into [PipelineDefinition] objects.
 * Uses SnakeYAML for parsing, then maps the untyped map structure into
 * the typed domain model.
 */
class PipelineYamlParser {

    private val yaml = Yaml()

    @Suppress("UNCHECKED_CAST")
    fun parse(yamlContent: String, filePath: String): PipelineDefinition {
        val rawRoot = yaml.load<Map<Any, Any?>>(yamlContent)
            ?: throw PipelineParseException("Empty pipeline file: $filePath")

        val root = rawRoot.mapKeys { it.key.toString() }

        val name = root["name"] as? String
            ?: throw PipelineParseException("Pipeline must have a 'name' field: $filePath")

        val onValue = rawRoot["on"] ?: rawRoot[true]

        // Top-level `requires:` is sugar: its entries apply to every ROOT job (jobs with no `needs`),
        // so "this repository's builds start only after its upstream" is one declaration, not one
        // per job. Injected here at parse time — everything downstream sees plain per-job lists.
        val topLevel = parseRequirements(root["requires"])
        val jobs = parseJobs(root["jobs"], filePath).mapValues { (_, job) ->
            if (job.needs.isEmpty() && (topLevel.artifacts.isNotEmpty() || topLevel.pipelines.isNotEmpty())) {
                job.copy(
                    requires = topLevel.artifacts + job.requires,
                    pipelineRequires = topLevel.pipelines + job.pipelineRequires,
                )
            } else {
                job
            }
        }

        return PipelineDefinition(
            name = name,
            triggers = parseTriggers(onValue),
            concurrency = parseConcurrency(root["concurrency"]),
            env = parseStringMap(root["env"]),
            secrets = parseStringList(root["secrets"]),
            environments = parseEnvironments(root["environments"]),
            jobs = jobs
        )
    }

    /**
     * The pipeline's environment declarations. The map key is the environment KEY — the
     * identity synced to workops. Strict: a malformed entry fails the parse.
     */
    private fun parseEnvironments(value: Any?): Map<String, EnvironmentDefinition> {
        if (value == null) return emptyMap()
        val map = value as? Map<*, *>
            ?: throw PipelineParseException("'environments' must be a map of environment key to its policy")
        return map.entries.associate { (key, raw) ->
            val envKey = key as? String ?: throw PipelineParseException("environment keys must be strings")
            if (raw == null || raw == true) return@associate envKey to EnvironmentDefinition()
            val envMap = raw as? Map<*, *>
                ?: throw PipelineParseException("environment '$envKey' must be a map (deploy-on, promotes-from, approval)")
            EnvironmentDefinition(
                deployOnRelease = envMap["deploy-on"] == "release",
                promotesFrom = envMap["promotes-from"] as? String,
                approval = envMap["approval"] == "required" || envMap["approval"] == true,
            ).let { envKey to it }
        }
    }

    fun validate(definition: PipelineDefinition): List<String> {
        val errors = mutableListOf<String>()
        if (definition.name.isBlank()) errors.add("Pipeline name must not be blank")
        if (definition.jobs.isEmpty()) errors.add("Pipeline must have at least one job")
        for (trigger in definition.triggers.filter { it.type == PipelineTriggerType.SCHEDULE }) {
            val cron = trigger.cron
            if (cron.isNullOrBlank()) {
                errors.add("Schedule trigger must declare a non-blank cron expression")
            } else {
                CronExpression.validate(cron)?.let { errors.add("Invalid schedule cron '$cron': $it") }
            }
        }
        // A pipeline requirement without an explicit ref correlates on the requiring run's own tag.
        // A pipeline that can never produce a tag run has nothing to correlate on — reject at
        // validation rather than letting the job wait forever on a ref that cannot exist.
        val hasTagTrigger = definition.triggers.any { it.type == PipelineTriggerType.TAG }
        for ((jobName, job) in definition.jobs) {
            // A steps-less job is a GATE — legal only when something gates it:
            // requirements to wait on or an approval to collect. Completed server-side, no agent.
            if (job.steps.isEmpty() &&
                job.requires.isEmpty() && job.pipelineRequires.isEmpty() && !job.approval
            ) {
                errors.add(
                    "Job '$jobName' must have at least one step (or be a gate: requires and/or approval with no steps)"
                )
            }
            val environment = job.environment
            if (environment != null && environment !in definition.environments) {
                errors.add(
                    "Job '$jobName' targets environment '$environment', which is not declared in this " +
                        "pipeline's 'environments' block"
                )
            }
            if (!hasTagTrigger) {
                for (req in job.pipelineRequires.filter { it.ref == null }) {
                    errors.add(
                        "Job '$jobName' requires pipeline '${req.pipeline}' without a 'ref', but this pipeline " +
                            "has no tag trigger to correlate on — add an explicit 'ref' to the requirement"
                    )
                }
            }
            for ((i, step) in job.steps.withIndex()) {
                if (step.run == null && step.uses == null) {
                    errors.add("Step ${i + 1} in job '$jobName' must have 'run' or 'uses'")
                }
                if (step.run != null && step.uses != null) {
                    errors.add("Step '${step.name}' in job '$jobName' cannot have both 'run' and 'uses'")
                }
            }
            for (dep in job.needs) {
                if (dep !in definition.jobs) {
                    errors.add("Job '$jobName' depends on unknown job '$dep'")
                }
            }
        }
        return errors
    }

    private fun parseTriggers(on: Any?): List<PipelineTrigger> {
        if (on == null) return emptyList()
        val triggers = mutableListOf<PipelineTrigger>()

        val onMap = when (on) {
            is Map<*, *> -> on
            is List<*> -> {
                on.filterIsInstance<String>().forEach { eventName ->
                    parseTriggerType(eventName)?.let { type ->
                        triggers.add(PipelineTrigger(type = type))
                    }
                }
                return triggers
            }
            else -> return emptyList()
        }

        for ((key, value) in onMap) {
            when (key as? String) {
                "push" -> triggers.add(parseBranchPathTrigger(PipelineTriggerType.PUSH, value))
                "pull_request" -> triggers.add(parseBranchPathTrigger(PipelineTriggerType.PULL_REQUEST, value))
                "tag" -> triggers.add(parseTagTrigger(value))
                "manual" -> if (value == true || value is Map<*, *>) {
                    triggers.add(
                        PipelineTrigger(
                            type = PipelineTriggerType.MANUAL,
                            inputs = parseTriggerInputs((value as? Map<*, *>)?.get("inputs")),
                        )
                    )
                }
                "schedule" -> {
                    val scheduleMap = value as? Map<*, *>
                    val cron = scheduleMap?.get("cron") as? String
                    if (cron != null) triggers.add(PipelineTrigger(type = PipelineTriggerType.SCHEDULE, cron = cron))
                }
                "release" -> triggers.add(parseReleaseishTrigger(PipelineTriggerType.RELEASE, value))
                "promotion" -> triggers.add(parseReleaseishTrigger(PipelineTriggerType.PROMOTION, value))
            }
        }
        return triggers
    }

    /**
     * A release-lifecycle trigger: `release: true` / `promotion: {...}`. Both accept
     * declared `inputs:`; promotion additionally accepts `environments:` — the environment keys this
     * pipeline promotes to.
     */
    private fun parseReleaseishTrigger(type: PipelineTriggerType, value: Any?): PipelineTrigger {
        if (value == null || value == true) return PipelineTrigger(type = type)
        val map = value as? Map<*, *> ?: return PipelineTrigger(type = type)
        return PipelineTrigger(
            type = type,
            environments = parseStringList(map["environments"]),
            inputs = parseTriggerInputs(map["inputs"]),
        )
    }

    /**
     * Declared trigger inputs. Strict like artifact declarations: a malformed input FAILS the parse —
     * a silently dropped input means a run starts without a value the pipeline depends on.
     */
    private fun parseTriggerInputs(value: Any?): Map<String, TriggerInput> {
        if (value == null) return emptyMap()
        val map = value as? Map<*, *>
            ?: throw PipelineParseException("'inputs' must be a map of input name to { type, default, description }")
        return map.entries.associate { (key, raw) ->
            val name = key as? String ?: throw PipelineParseException("input names must be strings")
            val inputMap = raw as? Map<*, *>
                ?: throw PipelineParseException("input '$name' must be a map with type/default/description")
            val type = inputMap["type"] as? String ?: "string"
            if (type !in TRIGGER_INPUT_TYPES) {
                throw PipelineParseException(
                    "input '$name' has unknown type '$type' — expected one of ${TRIGGER_INPUT_TYPES.joinToString()}"
                )
            }
            val options = parseStringList(inputMap["options"])
            if (type == "choice" && options.isEmpty()) {
                throw PipelineParseException("choice input '$name' must declare its 'options'")
            }
            name to TriggerInput(
                type = type,
                default = inputMap["default"]?.toString(),
                description = inputMap["description"] as? String,
                options = options,
            )
        }
    }

    private fun parseBranchPathTrigger(type: PipelineTriggerType, value: Any?): PipelineTrigger {
        if (value == null || value == true) return PipelineTrigger(type = type)
        val map = value as? Map<*, *> ?: return PipelineTrigger(type = type)
        return PipelineTrigger(
            type = type,
            branches = parseStringList(map["branches"]),
            paths = parseStringList(map["paths"]),
            pathsIgnore = parseStringList(map["paths-ignore"])
        )
    }

    private fun parseTagTrigger(value: Any?): PipelineTrigger {
        if (value == null || value == true) return PipelineTrigger(type = PipelineTriggerType.TAG)
        val map = value as? Map<*, *> ?: return PipelineTrigger(type = PipelineTriggerType.TAG)
        return PipelineTrigger(
            type = PipelineTriggerType.TAG,
            tags = parseStringList(map["patterns"])
        )
    }

    private fun parseConcurrency(value: Any?): PipelineConcurrency? {
        val map = value as? Map<*, *> ?: return null
        val group = map["group"] as? String ?: return null
        return PipelineConcurrency(
            group = group,
            cancelInProgress = map["cancel-in-progress"] as? Boolean ?: false
        )
    }

    private fun parseJobs(jobs: Any?, filePath: String): Map<String, JobDefinition> {
        val jobsMap = jobs as? Map<*, *>
            ?: throw PipelineParseException("Pipeline must have a 'jobs' section: $filePath")
        return jobsMap.entries.associate { (key, value) ->
            val jobName = key as? String ?: throw PipelineParseException("Job key must be a string: $filePath")
            val jobMap = value as? Map<*, *> ?: throw PipelineParseException("Job '$jobName' must be a map: $filePath")
            jobName to parseJob(jobMap)
        }
    }

    private fun parseJob(map: Map<*, *>): JobDefinition {
        val requirements = parseRequirements(map["requires"])
        return JobDefinition(
            runner = map["runner"] as? String ?: "default",
            timeout = parseTimeout(map["timeout"]),
            needs = parseStringList(map["needs"]),
            matrix = parseMatrix(map["matrix"]),
            condition = map["if"] as? String,
            secrets = parseStringList(map["secrets"]),
            steps = parseSteps(map["steps"]),
            artifacts = parseArtifacts(map["artifacts"]),
            requires = requirements.artifacts,
            pipelineRequires = requirements.pipelines,
            environment = map["environment"] as? String,
            approval = map["approval"] == true,
        )
    }

    /**
     * The job's produced-artifact declarations. A malformed entry FAILS the parse rather than being
     * dropped — a silently missing artifact declaration breaks release runs far downstream, where the
     * cause is invisible.
     */
    private fun parseArtifacts(value: Any?): List<ArtifactDefinition> {
        if (value == null) return emptyList()
        val list = value as? List<*>
            ?: throw PipelineParseException("'artifacts' must be a list of { type, namespace, coordinate }")
        return list.map { entry ->
            val map = entry as? Map<*, *>
                ?: throw PipelineParseException("each artifact must be a map with type, namespace, and coordinate")
            ArtifactDefinition(
                type = map["type"] as? String
                    ?: throw PipelineParseException("artifact is missing 'type' (docker, helm, maven, npm, raw, ml)"),
                namespace = map["namespace"] as? String
                    ?: throw PipelineParseException("artifact is missing 'namespace' (the registry namespace)"),
                coordinate = map["coordinate"] as? String
                    ?: throw PipelineParseException("artifact is missing 'coordinate' (e.g. \"my-api:\${'$'}{{ env.VERSION }}\")"),
                environments = when (val env = map["environments"]) {
                    null -> emptyList()
                    is List<*> -> env.map {
                        it as? String ?: throw PipelineParseException("artifact 'environments' entries must be strings")
                    }
                    else -> throw PipelineParseException("artifact 'environments' must be a list of environment names")
                },
            )
        }
    }

    /**
     * The job's requirement declarations — what must hold before the job may dispatch. One YAML list,
     * two entry kinds discriminated by their fields: an entry with `pipeline` is a pipeline-completion
     * requirement (`{ pipeline, repository, ref?, timeout? }`); anything else is an
     * artifact requirement (`{ type, namespace, coordinate, timeout? }`). Same strictness
     * as [parseArtifacts]: a malformed entry FAILS the parse, because a silently dropped requirement
     * means a consumer builds against a provider that isn't there yet — exactly the failure this
     * feature exists to prevent.
     */
    private fun parseRequirements(value: Any?): ParsedRequirements {
        if (value == null) return ParsedRequirements.EMPTY
        val list = value as? List<*>
            ?: throw PipelineParseException(
                "'requires' must be a list of { type, namespace, coordinate } or { pipeline, repository } entries"
            )
        val artifacts = mutableListOf<ArtifactRequirement>()
        val pipelines = mutableListOf<PipelineRequirement>()
        for (entry in list) {
            val map = entry as? Map<*, *>
                ?: throw PipelineParseException(
                    "each requirement must be a map — { type, namespace, coordinate } or { pipeline, repository }"
                )
            if (map.containsKey("pipeline")) {
                if (map.containsKey("type") || map.containsKey("namespace") || map.containsKey("coordinate")) {
                    throw PipelineParseException(
                        "a requirement is either an artifact (type/namespace/coordinate) or a pipeline " +
                            "(pipeline/repository) — not both"
                    )
                }
                pipelines.add(
                    PipelineRequirement(
                        pipeline = map["pipeline"] as? String
                            ?: throw PipelineParseException("pipeline requirement's 'pipeline' must be the upstream pipeline's name"),
                        repository = map["repository"] as? String
                            ?: throw PipelineParseException(
                                "pipeline requirement is missing 'repository' (the upstream repository, 'owner/slug' or a sibling 'slug')"
                            ),
                        ref = map["ref"] as? String,
                        timeout = parseTimeout(map["timeout"]) ?: ArtifactRequirement.DEFAULT_TIMEOUT,
                    )
                )
            } else {
                artifacts.add(
                    ArtifactRequirement(
                        type = map["type"] as? String
                            ?: throw PipelineParseException("requirement is missing 'type' (docker, helm, maven, npm, raw, ml)"),
                        namespace = map["namespace"] as? String
                            ?: throw PipelineParseException("requirement is missing 'namespace' (the registry namespace)"),
                        coordinate = map["coordinate"] as? String
                            ?: throw PipelineParseException("requirement is missing 'coordinate' (e.g. \"my-lib:\${'$'}{{ version }}\")"),
                        timeout = parseTimeout(map["timeout"]) ?: ArtifactRequirement.DEFAULT_TIMEOUT,
                    )
                )
            }
        }
        return ParsedRequirements(artifacts, pipelines)
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
                workingDirectory = stepMap["working-directory"] as? String,
                with = parseStringMap(stepMap["with"]),
                env = parseStringMap(stepMap["env"])
            )
        }
    }

    private fun parseMatrix(matrix: Any?): Map<String, List<String>>? {
        val matrixMap = matrix as? Map<*, *> ?: return null
        return matrixMap.entries.associate { (key, value) ->
            (key as? String ?: "") to parseStringList(value)
        }
    }

    private fun parseTimeout(value: Any?): Duration? {
        val str = value?.toString() ?: return null
        return when {
            str.endsWith("h") -> str.removeSuffix("h").toDoubleOrNull()?.hours
            str.endsWith("m") -> str.removeSuffix("m").toDoubleOrNull()?.minutes
            else -> str.toDoubleOrNull()?.minutes
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
        return map.entries.associate { (k, v) -> k.toString() to flattenValue(v) }
    }

    private fun flattenValue(value: Any?): String {
        return when (value) {
            null -> ""
            is List<*> -> value.joinToString(",") { it?.toString() ?: "" }
            is String -> value
            else -> value.toString()
        }
    }

    private fun parseTriggerType(name: String): PipelineTriggerType? {
        return when (name.lowercase()) {
            "push" -> PipelineTriggerType.PUSH
            "pull_request" -> PipelineTriggerType.PULL_REQUEST
            "tag" -> PipelineTriggerType.TAG
            "manual" -> PipelineTriggerType.MANUAL
            "schedule" -> PipelineTriggerType.SCHEDULE
            else -> null
        }
    }
}

class PipelineParseException(message: String) : RuntimeException(message)

private val TRIGGER_INPUT_TYPES = setOf("string", "boolean", "number", "choice")

/** A `requires:` list split into its two entry kinds. */
private data class ParsedRequirements(
    val artifacts: List<ArtifactRequirement>,
    val pipelines: List<PipelineRequirement>,
) {
    companion object {
        val EMPTY = ParsedRequirements(emptyList(), emptyList())
    }
}
