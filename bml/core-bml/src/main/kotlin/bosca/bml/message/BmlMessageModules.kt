package bosca.bml.message

/**
 * Reflection-free discovery handshake used when a host initializes a generated
 * `bml.generated.BmlMessages` object from a child classloader.
 */
object BmlMessageModules {
    private val pending = ThreadLocal<BmlMessageModule?>()

    /** Register the generated module initialized on the current thread. */
    @JvmStatic
    fun register(module: BmlMessageModule) {
        pending.set(module)
    }

    /** Claim and clear the module registered by the class initialization just triggered. */
    fun claim(): BmlMessageModule? {
        val module = pending.get()
        pending.remove()
        return module
    }
}
