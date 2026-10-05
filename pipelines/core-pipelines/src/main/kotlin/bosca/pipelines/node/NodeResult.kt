package bosca.pipelines.node

/**
 * What a node produced when the executor ran it — either a finished value or a request to suspend.
 *
 * Most nodes are synchronous and always produce [Output]; they implement
 * [PipelineNode.execute] and inherit the default [PipelineNode.run] which wraps it as [Output]. A
 * node that hands work off to an out-of-band job (and wants the run to park until that job finishes,
 * rather than blocking a worker) overrides [PipelineNode.run] and returns [Suspend].
 */
sealed interface NodeResult {

    /** The node finished and produced [value] (`null` = no output, exactly as `execute` returning null). */
    data class Output(val value: PipelineValue?) : NodeResult

    /**
     * The node parked: it has out-of-band work that must complete before it can produce a value.
     *
     * [enqueue] schedules that backing work. The executor runs it **after** the suspended checkpoint
     * is durably persisted, so a fast-completing job can never try to resume a run whose suspended
     * state has not been written yet. The executor records the parked node by its id as the run's
     * outstanding await.
     */
    data class Suspend(
        val enqueue: suspend () -> Unit,
    ) : NodeResult

    val isOutput: Boolean get() = this is Output

    val result: PipelineValue? get() = (this as? Output)?.value
}
