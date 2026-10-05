package bosca.sharedqueue.jobs

import kotlin.time.Duration

open class DelayException(val time: Duration) : Exception()