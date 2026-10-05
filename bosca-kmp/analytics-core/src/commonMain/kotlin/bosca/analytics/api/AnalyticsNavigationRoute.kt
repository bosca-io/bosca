package bosca.analytics.api

/** Stable analytics identity for a key rendered by a navigation framework. */
interface AnalyticsNavigationRoute {
    /** Page path reported when this key becomes the active destination. */
    val analyticsPath: String

    /** Optional human-readable destination title. */
    val analyticsTitle: String?
        get() = null

    /** Additional non-sensitive metadata attached to the navigation event. */
    val analyticsExtras: Map<String, String>
        get() = emptyMap()
}
