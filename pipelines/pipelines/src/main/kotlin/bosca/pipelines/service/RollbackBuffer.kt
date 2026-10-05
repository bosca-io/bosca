package bosca.pipelines.service

import bosca.pipelines.node.RollbackEvent
import bosca.pipelines.node.RollbackSink
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The durable run's [RollbackSink]: the executor's concurrent fan-out
 * records a node's completion (when it declares a rollback pipeline) here, and the run service [drain]s
 * and persists them to the rollback log **after** the drive returns — keeping the executor
 * repository-free, exactly like [NodeExecutionBuffer]. One buffer per drive; drained once, in
 * completion order.
 */
class RollbackBuffer : RollbackSink {

    private val events = ConcurrentLinkedQueue<RollbackEvent>()

    override fun record(event: RollbackEvent) {
        events.add(event)
    }

    /** Remove and return everything recorded so far, oldest first — called once after the drive. */
    fun drain(): List<RollbackEvent> {
        val drained = ArrayList<RollbackEvent>(events.size)
        while (true) {
            val e = events.poll() ?: break
            drained.add(e)
        }
        return drained
    }
}
