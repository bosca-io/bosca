package bosca.storage.events

import bosca.events.Event
import bosca.serialization.UUID

interface StorageSystemEvent : Event {

    val id: UUID
}