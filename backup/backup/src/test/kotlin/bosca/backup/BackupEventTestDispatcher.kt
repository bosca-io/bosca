package bosca.backup

import bosca.db.connectionOrNull
import bosca.events.Event
import bosca.pipelines.PipelineEventDispatcher
import kotlinx.serialization.KSerializer
import kotlin.test.assertTrue

/** Records the generated event hook while checking its publication transaction. */
internal class BackupEventTestDispatcher : PipelineEventDispatcher {
    val events = mutableListOf<BackupCompleted>()
    var beforeDispatch: suspend (BackupCompleted) -> Unit = {}

    override suspend fun <T : Event> dispatch(eventName: String, event: T, serializer: KSerializer<T>) {
        if (event !is BackupCompleted) return
        assertTrue(connectionOrNull()?.inTransaction == true)
        beforeDispatch(event)
        events.add(event)
    }
}
