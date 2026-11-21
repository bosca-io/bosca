package bosca.workops.ui

import bosca.workops.ui.graphql.IWorkOpsReleaseHttpCounter
import bosca.workops.ui.graphql.IWorkOpsReleaseSessionCounter
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/** The API's aggregate and dense series are retained without adding client-generated samples. */
@Serializable
data class WorkOpsHealthCounter(val value: Long, val samples: List<WorkOpsHealthSample>)

internal fun IWorkOpsReleaseSessionCounter.healthCounter() = WorkOpsHealthCounter(value, values.map { healthSample(it.time, it.value) })
internal fun IWorkOpsReleaseHttpCounter.healthCounter() = WorkOpsHealthCounter(value, values.map { healthSample(it.time, it.value) })

private fun healthSample(time: String, value: Long) = WorkOpsHealthSample(OffsetDateTime.parse(time).toInstant().toString(), value)
