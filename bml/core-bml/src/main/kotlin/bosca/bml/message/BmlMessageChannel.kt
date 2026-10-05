package bosca.bml.message

/** One output channel selected for a BML message render; null on the context renders all channels. */
enum class BmlMessageChannel {
    EMAIL,
    PUSH,
}
