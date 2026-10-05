package bosca.analytics.compose

internal class InputFocusTracker {
    private var focusedAt: Long? = null

    fun focus(nowMillis: Long): Boolean {
        if (focusedAt != null) return false
        focusedAt = nowMillis
        return true
    }

    fun blur(nowMillis: Long): Long? {
        val started = focusedAt ?: return null
        focusedAt = null
        return (nowMillis - started).coerceAtLeast(0)
    }
}
