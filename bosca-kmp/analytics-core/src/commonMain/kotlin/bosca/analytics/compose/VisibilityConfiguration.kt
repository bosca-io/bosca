package bosca.analytics.compose

internal fun validateVisibilityConfiguration(threshold: Float, dwellMillis: Long) {
    require(threshold in 0f..1f) { "Visibility threshold must be between zero and one" }
    require(dwellMillis >= 0) { "Visibility dwell time cannot be negative" }
}
