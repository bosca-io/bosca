package bosca.workops.ui

import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/** A reported counter bucket, retaining its real timestamp instead of a fabricated baseline. */
@Serializable
data class WorkOpsHealthSample(val time: String, val value: Long) {
    val instant get() = OffsetDateTime.parse(time).toInstant()
}
