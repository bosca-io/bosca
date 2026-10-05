package bosca.experimentation.service

import bosca.experimentation.model.AnalysisReport
import bosca.experimentation.model.Assignment
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ConversionGoalInput
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentInput
import bosca.experimentation.model.ExperimentResult
import bosca.experimentation.model.ExperimentStatus
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing experiments — observation and stats attached to a single
 * targeting rule on a feature flag.
 *
 * Experiments do not own values or variants; the rule's rollout determines which
 * variations are served and in what proportions. The experiment's job is to record
 * exposure events, aggregate them per variation, and compute statistical lift over
 * the rule's control variation.
 */
interface ExperimentService : Service {

    /**
     * Retrieves a paginated list of all experiments, ordered by creation date descending.
     */
    suspend fun getAll(offset: Long, limit: Int): List<Experiment>

    /**
     * Retrieves a single experiment by its unique identifier.
     */
    suspend fun getById(id: UUID): Experiment?

    /**
     * Retrieves all experiments linked to a specific feature flag.
     */
    suspend fun getByFlagId(flagId: UUID): List<Experiment>

    /**
     * Retrieves a page of experiments linked to a specific feature flag,
     * ordered by creation date descending. Pagination is applied in the
     * database query so flags with long experiment histories do not
     * fan out unboundedly.
     */
    suspend fun getByFlagId(flagId: UUID, offset: Long, limit: Int): List<Experiment>

    /**
     * Creates a new experiment from the provided input specification. The
     * [ExperimentInput.targetingRuleId] must point to an existing rule on the
     * referenced flag (or null to attach to the flag's default-variation path).
     */
    suspend fun add(input: ExperimentInput): Experiment

    /**
     * Updates an existing experiment's definition. The targeting rule attachment
     * may only be changed while the experiment is in DRAFT — once it has started
     * collecting assignments, the rule it observes is locked in.
     */
    suspend fun edit(id: UUID, input: ExperimentInput): Experiment

    /**
     * Permanently deletes an experiment and all associated assignments, goals, and results.
     */
    suspend fun delete(id: UUID)

    /**
     * Updates the lifecycle status of an experiment. Transitioning to RUNNING
     * verifies that the experiment's attached rule still exists and that the
     * rule's rollout has at least two distinct variation weights (otherwise
     * there's nothing to compare against the control).
     */
    suspend fun setStatus(id: UUID, status: ExperimentStatus): Experiment

    /**
     * Records or returns the variation assignment for an installation within
     * an experiment. [installationId] is always required; [principalId] adds
     * authenticated ownership when available. An anonymous assignment is
     * claimed in place after login, preserving its variation and assignment
     * time. Bucketing uses the parent flag's targeting rule rollout (the rule
     * the experiment is attached to). If the rollout has changed since the
     * installation was first assigned, the original assignment is returned.
     */
    suspend fun assignVariation(experimentId: UUID, principalId: UUID?, installationId: String): Assignment

    /**
     * Retrieves the existing variation assignment for an installation and its
     * optional authenticated principal. Results are cached by the complete
     * identity tuple.
     */
    suspend fun getAssignment(experimentId: UUID, principalId: UUID?, installationId: String): Assignment?

    /**
     * Retrieves all conversion goals defined for an experiment.
     */
    suspend fun getConversionGoals(experimentId: UUID): List<ConversionGoal>

    /**
     * Retrieves a page of conversion goals defined for an experiment,
     * ordered by creation time. Pagination is applied in the database
     * query.
     */
    suspend fun getConversionGoals(experimentId: UUID, offset: Long, limit: Int): List<ConversionGoal>

    /**
     * Adds a new conversion goal to an experiment.
     */
    suspend fun addConversionGoal(experimentId: UUID, input: ConversionGoalInput): ConversionGoal

    /**
     * Updates the editable fields (name, filter shape, metric type) on an
     * existing conversion goal. The owning experiment id and the goal's
     * created timestamp are immutable; any aggregated rows in
     * `experiment_results` keyed off this goal id are preserved across the
     * edit, so a previously aggregated experiment will recompute against
     * the new filter on the next aggregation run.
     */
    suspend fun editConversionGoal(id: UUID, input: ConversionGoalInput): ConversionGoal

    /**
     * Permanently deletes a conversion goal.
     */
    suspend fun deleteConversionGoal(id: UUID)

    /**
     * Retrieves aggregated experiment results for all variation/goal combinations.
     */
    suspend fun getResults(experimentId: UUID): List<ExperimentResult>

    /**
     * Retrieves a page of aggregated experiment results, ordered by
     * variation key and goal id. Pagination is applied in the database query.
     */
    suspend fun getResults(experimentId: UUID, offset: Long, limit: Int): List<ExperimentResult>

    /**
     * Retrieves all statistical analysis reports for an experiment, ordered by creation date descending.
     */
    suspend fun getAnalysisReports(experimentId: UUID): List<AnalysisReport>

    /**
     * Retrieves a page of statistical analysis reports for an experiment,
     * ordered by creation date descending. Pagination is applied in the
     * database query.
     */
    suspend fun getAnalysisReports(experimentId: UUID, offset: Long, limit: Int): List<AnalysisReport>

    /**
     * Retrieves analysis reports with the newest report for the supplied current
     * definition first, followed by remaining history newest-first. This keeps
     * the current report present in a bounded first page even when a stale
     * analysis finishes later.
     */
    suspend fun getAnalysisReportsForRevision(
        experimentId: UUID,
        controlVariationKey: String,
        analysisRevision: Long,
        offset: Long,
        limit: Int,
    ): List<AnalysisReport>

    /**
     * Retrieves a page of rollout controller audit events for an experiment,
     * ordered by creation date descending. Pagination is applied in the
     * database query.
     */
    suspend fun getRolloutPolicyEvents(experimentId: UUID, offset: Long, limit: Int): List<bosca.experimentation.model.RolloutPolicyEvent>
}
