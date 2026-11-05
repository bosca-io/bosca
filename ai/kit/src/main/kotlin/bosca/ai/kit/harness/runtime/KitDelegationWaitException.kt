package bosca.ai.kit.harness.runtime

import bosca.sharedqueue.jobs.DelayException
import kotlin.time.Duration.Companion.seconds

/** Detaches observation of an already-sent native turn; receipt reconciliation never resubmits it. */
internal class KitDelegationWaitException : DelayException(5.seconds)
