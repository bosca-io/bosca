package bosca.analytics.queue

import java.io.IOException

internal class CorruptAnalyticsSegmentException(message: String) : IOException(message)
