package bosca.bml.message

/** Reflection-free manifest of every [BmlMessageTemplate] compiled in one project jar. */
interface BmlMessageModule {
    val templates: List<BmlMessageTemplate>
}
