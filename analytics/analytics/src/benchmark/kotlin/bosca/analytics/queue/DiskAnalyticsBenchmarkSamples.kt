package bosca.analytics.queue

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.ceil

/** A fixed histogram keeps latency measurement memory bounded during long runs. */
internal class DiskAnalyticsBenchmarkSamples {
    private val buckets = LongArray(100_001)
    private var count = 0L
    private var maximum = 0L

    @Synchronized
    fun add(nanos: Long) {
        // 100 microsecond resolution, with a final overflow bucket at ten seconds.
        buckets[(nanos.coerceAtLeast(0) / 100_000).coerceAtMost(buckets.lastIndex.toLong()).toInt()]++
        count++
        maximum = maxOf(maximum, nanos)
    }
    @Synchronized
    fun json(): JsonObject = buildJsonObject {
        put("samples", count)
        for (percentile in listOf(50, 95, 99)) {
            val target = ceil(count * percentile / 100.0).toLong()
            var total = 0L
            var index = 0
            while (index < buckets.lastIndex) {
                total += buckets[index]
                if (total >= target) break
                index++
            }
            put("p${percentile}Millis", if (count == 0L) 0.0 else (index + 1) / 10.0)
        }
        put("maxMillis", maximum / 1_000_000.0)
    }
}
