package bosca.pipelines.service

import bosca.pipelines.node.NodeExecutionEvent
import bosca.pipelines.node.NodeExecutionSink
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The durable run's [NodeExecutionSink]: the executor's concurrent fan-out records
 * per-node events here, into a thread-safe in-memory queue, and the run service [drain]s and persists
 * them **after** the drive returns. Buffering keeps the executor repository-free and avoids issuing
 * DB writes on the run's connection from inside the concurrent fan-out.
 *
 * One buffer per drive (a fresh run, or one resume re-drive): its contents are exactly the events of
 * that drive, flushed once.
 */
class NodeExecutionBuffer : NodeExecutionSink {

    private val events = ConcurrentLinkedQueue<NodeExecutionEvent>()

    override fun record(event: NodeExecutionEvent) {
        events.add(event)
    }

    /** Remove and return everything recorded so far, oldest first — called once after the drive. */
    fun drain(): List<NodeExecutionEvent> {
        val drained = ArrayList<NodeExecutionEvent>(events.size)
        while (true) {
            val e = events.poll() ?: break
            drained.add(e)
        }
        return drained
    }
}
