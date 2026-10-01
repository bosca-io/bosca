package bosca.git.model

import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * In-memory representation of a parsed pipeline YAML file.
 * This is the intermediate form between raw YAML and the persisted
 * [Pipeline] + [PipelineJob] + [PipelineStep] records.
 */
@Serializable
data class PipelineDefinition(
    val name: String,
    val triggers: List<PipelineTrigger> = emptyList(),
    val concurrency: PipelineConcurrency? = null,
    val env: Map<String, String> = emptyMap(),
    val secrets: List<String> = emptyList(),
    /**
     * The environments this pipeline deploys to, keyed by environment KEY — the
     * identity that links to the workops Environment entity. Declared in a release pipeline; jobs
     * bind via [JobDefinition.environment].
     */
    val environments: Map<String, EnvironmentDefinition> = emptyMap(),
    val jobs: Map<String, JobDefinition> = emptyMap()
)

/**
 * The exact trigger parameters and jobs git-ci would persist for a run, without creating rows.
 * Release cockpits use this to render the same plan [bosca.git.service.PipelineRunService.createRun]
 * will execute instead of duplicating condition and environment-selection semantics in a client.
 */
data class PipelineExecutionPlan(
    val parameters: Map<String, String>,
    val jobs: Map<String, JobDefinition>,
)

/**
 * One environment declared in a release pipeline. The map key in
 * [PipelineDefinition.environments] is the environment's KEY (`production`); this carries its
 * policy: [deployOnRelease] includes the environment's jobs in RELEASE runs (a promotion run is the
 * only other way an environment's jobs execute), [promotesFrom] names the environment a release
 * must hold before promoting here, and [approval] parks the environment's jobs for a human before
 * dispatch.
 */
@Serializable
data class EnvironmentDefinition(
    val deployOnRelease: Boolean = false,
    val promotesFrom: String? = null,
    val approval: Boolean = false,
)

/**
 * A job definition parsed from YAML, before expansion into
 * [PipelineJob] records (matrix expansion, step creation).
 */
@Serializable
data class JobDefinition(
    val runner: String = "default",
    val timeout: Duration? = null,
    val needs: List<String> = emptyList(),
    val matrix: Map<String, List<String>>? = null,
    val condition: String? = null,
    /**
     * The secret NAMES this job may resolve. The pipeline-level `secrets:` list is
     * flattened in at run creation; a job-level list adds job-specific names. Empty means the
     * pipeline declared none — resolution falls back to the repository's secrets (legacy).
     */
    val secrets: List<String> = emptyList(),
    val steps: List<StepDefinition> = emptyList(),
    /**
     * The artifacts this job produces. The build owns creating, versioning, and
     * publishing them; this only *declares* what came out — by its registry coordinates — so the run
     * can report it and downstream pipeline nodes can resolve "what's available". Empty for jobs that
     * produce nothing.
     */
    val artifacts: List<ArtifactDefinition> = emptyList(),
    /**
     * The artifacts this job requires before it may dispatch — the consumption dual of
     * [artifacts]. A job is claimable only when its `needs` are satisfied AND every requirement
     * resolves in the artifact registry, so cross-repo build dependencies gate on the real
     * precondition (the provider's artifacts exist) instead of orchestration order. Empty for jobs
     * with no cross-repo dependencies.
     */
    val requires: List<ArtifactRequirement> = emptyList(),
    /**
     * The upstream pipeline runs this job requires before it may dispatch — the
     * pipeline-completion form of [requires]. Both kinds are authored under the same YAML `requires:`
     * key (an entry's fields determine which it is); the parser splits them. An artifact requirement
     * proves the artifact exists; a pipeline requirement proves the upstream pipeline's run for the
     * same tag COMPLETED — which also covers work the upstream does after publishing (version pin
     * bumps, submodule pushes). Empty for jobs with no upstream pipeline dependencies.
     */
    val pipelineRequires: List<PipelineRequirement> = emptyList(),
    /**
     * The environment this job targets, by KEY from [PipelineDefinition.environments].
     * Environment-bound jobs run only when their environment is active: in a RELEASE run when the
     * environment declares deploy-on-release, or in the PROMOTION run targeting it. Inherits the
     * environment's approval policy. Null for ordinary jobs.
     */
    val environment: String? = null,
    /**
     * Parks this job for a human approval before dispatch — set directly in YAML
     * (`approval: true`, a standalone checkpoint) or inherited from the bound [environment]'s
     * policy at run creation. With no steps, the job is a pure manual gate.
     */
    val approval: Boolean = false,
)

/**
 * A produced-artifact declaration in a job's YAML: enough to resolve the published artifact in the
 * registry, which needs a [namespace], a [type], and the [coordinate] — a **type-specific** identifier
 * that encodes the repository and version in that type's own form (Maven `group:artifact:version`,
 * Docker `image:tag`, etc.). A bare coordinate isn't enough on its own — the namespace and type sit
 * outside it. Example: `{ type: docker, namespace: bosca-docker, coordinate: "my-api:${{ version }}" }`.
 *
 * [coordinate] is a template resolved once, at run creation, via [bosca.git.ci.parser.PipelineExpressionParser]
 * against the run context — `${{ ref }}` / `${{ branch }}`, `${{ tag }}` (the bare tag name, e.g.
 * `v1.4.0`), `${{ version }}` (the tag minus a leading `v` — matches the workops version the release
 * relay tagged), and `${{ env.X }}` (workflow env + trigger parameters). Step-exported env is NOT
 * available (steps haven't run yet), and neither are `${{ matrix.X }}` values. Purely declarative —
 * the build still creates and publishes the artifact; this only reports what came out.
 */
@Serializable
data class ArtifactDefinition(
    /** Registry type the coordinate resolves against: `docker`, `helm`, `maven`, `npm`, `raw`, `ml`. */
    val type: String,
    /** Registry namespace the artifact was published to, e.g. `bosca-maven`. */
    val namespace: String,
    /** Type-specific coordinate (repository + version in that type's form); supports `${{ }}` interpolation. */
    val coordinate: String,
    /**
     * The environments this artifact serves, by environment (type) name — e.g. a per-environment Helm
     * values artifact declares `environments: ["development"]`. Empty = not environment-scoped (serves
     * every environment). Deploy targets use this to pick the right publication for their environment.
     */
    val environments: List<String> = emptyList()
)

/**
 * A required-artifact declaration in a job's YAML: the registry artifact that must
 * exist before the job may dispatch. Same addressing as [ArtifactDefinition] — a [namespace], a
 * [type], and a type-specific [coordinate] resolved once at run creation via the same `${{ }}`
 * interpolation. A coordinate ending in `*` is a prefix constraint (`my-lib:6.0.*` matches any
 * published `6.0.x`), mirroring the release-validation constraint language.
 *
 * [timeout] is the requirement's own clock — how long the job may WAIT for the artifact before
 * failing loudly — deliberately separate from the job's execution timeout, so waiting 40 minutes
 * for a provider never eats into the build's time budget.
 *
 * A later patch tag may explicitly reuse an earlier patch's requirement by including
 * `Patch for [X.Y.Z]` (or `Bosca-Patch-For: X.Y.Z`) in the annotated tag or a commit after the
 * most recent lower ancestor patch tag. The checker then tries the exact coordinate first and
 * falls back only by replacing the current same-major/minor, higher-patch version token with
 * `X.Y.Z`.
 */
@Serializable
data class ArtifactRequirement(
    /** Registry type the coordinate resolves against: `docker`, `helm`, `maven`, `npm`, `raw`, `ml`. */
    val type: String,
    /** Registry namespace the artifact must exist in, e.g. `bosca-maven`. */
    val namespace: String,
    /** Type-specific coordinate; supports `${{ }}` interpolation and a trailing-`*` prefix constraint. */
    val coordinate: String,
    /** How long the job may wait for this artifact before failing; default [DEFAULT_TIMEOUT]. */
    val timeout: Duration = DEFAULT_TIMEOUT,
) {
    companion object {
        val DEFAULT_TIMEOUT: Duration = kotlin.time.Duration.parse("30m")
    }
}

/**
 * A required-pipeline declaration in a job's YAML: another repository's pipeline whose
 * run must have COMPLETED successfully before this job may dispatch. Authored under the same
 * `requires:` key as [ArtifactRequirement] — an entry with a `pipeline` field is this form.
 *
 * Correlation is by ref: the requirement is satisfied when [repository]'s pipeline named [pipeline]
 * has a run whose ref equals [ref] (latest run by number) that finished SUCCESS. In YAML, [ref] is
 * optional — when omitted it resolves to the requiring run's own ref at run creation, so a tag
 * `6.2.0` on the consumer waits for the provider's run for tag `6.2.0`. An explicit `ref` supports
 * `${{ }}` interpolation (e.g. `refs/tags/v${{ version }}`) for divergent tag formats. This is an
 * ordering constraint, not mutual exclusion: an upstream run for a different ref neither blocks nor
 * satisfies it.
 *
 * An upstream run that finished FAILURE or CANCELLED fails the requiring job loudly, naming this
 * dependency — a consumer never builds on a failed provider. No upstream run yet means keep waiting
 * (the upstream repository may be tagged later), bounded by [timeout] exactly like an artifact
 * requirement's deadline.
 *
 * The same explicit patch marker described by [ArtifactRequirement] allows a missing exact
 * higher-patch upstream ref to fall back to the successful marked-base run. An existing exact run
 * always wins, including its failure or in-progress state.
 */
@Serializable
data class PipelineRequirement(
    /** The upstream pipeline's name (its YAML `name:`), unique enough within the repository. */
    val pipeline: String,
    /**
     * The upstream repository — `owner/slug`, or a bare `slug` resolved within the requiring
     * repository's owner namespace (sibling repositories, the common case).
     */
    val repository: String,
    /**
     * The correlation ref. Null in YAML = the requiring run's own ref, resolved once at run
     * creation (the persisted form is always concrete). Supports `${{ }}` interpolation.
     */
    val ref: String? = null,
    /** How long the job may wait for the upstream run before failing; default [ArtifactRequirement.DEFAULT_TIMEOUT]. */
    val timeout: Duration = ArtifactRequirement.DEFAULT_TIMEOUT,
)

/**
 * A step definition parsed from YAML. Either [run] (shell command)
 * or [uses] (built-in action) must be set, not both.
 */
@Serializable
data class StepDefinition(
    val name: String,
    val uses: String? = null,
    val run: String? = null,
    val image: String? = null,
    val condition: String? = null,
    val workingDirectory: String? = null,
    val with: Map<String, String> = emptyMap(),
    val env: Map<String, String> = emptyMap()
)
