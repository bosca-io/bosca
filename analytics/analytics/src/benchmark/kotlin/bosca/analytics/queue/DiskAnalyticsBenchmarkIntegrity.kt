package bosca.analytics.queue

import bosca.analytics.model.Events
import java.util.BitSet

/** Counts each synthetic identity once and checks that drain/recovery did not lose events. */
internal class DiskAnalyticsBenchmarkIntegrity {
    private val seen = BitSet()
    private var count = 0L
    @Synchronized
    fun accept(events: Events) {
        for (event in events.events) {
            val id = checkNotNull(event.clientId).substringAfterLast('-').toInt()
            check(!seen[id]) { "Duplicate benchmark event $id" }
            seen.set(id)
            count++
        }
    }
    @Synchronized
    fun verify(expected: Long) {
        check(count == expected && seen.nextClearBit(0).toLong() == expected) {
            "Benchmark integrity failed: received=$count expected=$expected firstMissing=${seen.nextClearBit(0)}"
        }
    }
}
