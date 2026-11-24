package bosca.analytics.queue

import java.io.IOException

internal class DiskAnalyticsCapacityException(maxBytes: Long) : IOException("Analytics spool capacity exceeded: $maxBytes bytes")
