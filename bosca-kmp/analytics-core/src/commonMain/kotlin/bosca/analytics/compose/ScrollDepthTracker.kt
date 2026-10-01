package bosca.analytics.compose

internal class ScrollDepthTracker(
    private val marks: List<Int> = listOf(25, 50, 75, 90, 100),
) {
    private val reached = mutableSetOf<Int>()
    var maximum: Int = 0
        private set

    fun update(percent: Int): List<Int> {
        val bounded = percent.coerceIn(0, 100)
        maximum = maxOf(maximum, bounded)
        return marks.filter { bounded >= it && reached.add(it) }
    }
}
