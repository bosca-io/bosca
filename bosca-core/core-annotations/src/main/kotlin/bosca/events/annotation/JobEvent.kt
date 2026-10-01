package bosca.events.annotation

import bosca.queue.annotations.IJobDefinition
import kotlin.reflect.KClass

/**
 * Marks a [Event][bosca.events.Event] data class as a dispatchable platform event.
 *
 * KSP generates a `dispatch()` extension that enqueues [jobs], publishes to
 * [pubsubChannel] (when set), and forwards the event to the script-trigger hook.
 * The same annotation feeds the Event Catalog, which enumerates every event so
 * trigger/automation UIs can offer event pickers and field-aware filter builders.
 *
 * @property jobs the job definitions enqueued when this event is dispatched
 * @property pubsubChannel the pub/sub channel the event is published to; blank to skip publishing
 * @property displayName a human-friendly name for this event in catalog/picker UIs;
 *   blank falls back to the event's simple class name
 * @property description a short explanation of when this event fires, shown in catalog UIs
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class JobEvent(
    val jobs: Array<KClass<out IJobDefinition>> = [],
    val pubsubChannel: String = "",
    val displayName: String = "",
    val description: String = ""
)
