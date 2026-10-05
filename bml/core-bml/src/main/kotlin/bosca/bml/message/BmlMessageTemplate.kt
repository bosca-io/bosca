package bosca.bml.message

import kotlinx.serialization.KSerializer

/** A compiled BML `<message>` unit with one or more channel render regions. */
interface BmlMessageTemplate {
    /** Stable key within the containing BML message project. */
    val key: String

    /** Whether this unit declares an `<email>` region. */
    val supportsEmail: Boolean get() = false

    /** Whether this unit declares a `<push>` region. */
    val supportsPush: Boolean get() = false

    /** Serializer for the unit's typed payload when statically discoverable by [PayloadSample]. */
    val payloadSerializer: KSerializer<*>? get() = null

    /** Render every declared channel against one shared [message] context. */
    suspend fun renderMessage(message: BmlMessageContext): RenderedMessage
}
