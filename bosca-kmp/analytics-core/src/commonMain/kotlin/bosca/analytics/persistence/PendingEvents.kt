package bosca.analytics.persistence

internal class PendingEvents(
    private val generation: Long,
    val eventCount: Int,
    val groups: List<ContextEvents>,
    private val queue: EventQueue,
) {
    suspend fun finish(group: ContextEvents) = queue.finish(group)

    suspend fun close(): Boolean = queue.close(generation)
}
