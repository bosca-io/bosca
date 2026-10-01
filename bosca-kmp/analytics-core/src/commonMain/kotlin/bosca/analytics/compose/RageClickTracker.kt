package bosca.analytics.compose

internal class RageClickTracker(
    private val threshold: Int = 3,
    private val windowMillis: Long = 1_000,
) {
    private val clicks = ArrayDeque<Long>()

    init {
        require(threshold > 1) { "Rage click threshold must be greater than one" }
        require(windowMillis > 0) { "Rage click window must be positive" }
    }

    fun record(nowMillis: Long): Int? {
        while (clicks.firstOrNull()?.let { nowMillis - it >= windowMillis } == true) {
            clicks.removeFirst()
        }
        clicks.addLast(nowMillis)
        if (clicks.size < threshold) return null
        return clicks.size.also { clicks.clear() }
    }
}
