package bosca.bml.message.host

import bosca.bml.message.BmlMessageContext
import bosca.bml.message.BmlMessageChannel
import bosca.bml.message.BmlMessageModule
import bosca.bml.message.BmlMessageModules
import bosca.bml.message.BmlMessageTemplate
import bosca.bml.message.RenderedMessage
import java.io.File
import java.net.URLClassLoader

/**
 * One loaded message jar: a child [URLClassLoader] over the jar — parented to
 * the host's classloader so `core-bml` types are shared, never duplicated — plus the templates
 * discovered through the jar's well-known [BmlMessageModule] entry point (`bml.generated.BmlMessages`,
 * no reflection scanning). The jar is a rendering plugin invoked through the statically-present
 * [BmlMessageTemplate] interface; nothing else in it is executed.
 */
class BmlMessageJar private constructor(
    val jarFile: File,
    private val loader: URLClassLoader,
    val templates: Map<String, BmlMessageTemplate>,
) : AutoCloseable {

    /** Render every channel declared by [templateKey] against [message]. */
    suspend fun render(templateKey: String, message: BmlMessageContext): RenderedMessage {
        val template = templates[templateKey] ?: throw BmlMessageHostException(
            "Unknown message template '$templateKey' in ${jarFile.name}; available: ${templates.keys.sorted()}",
        )
        when (message.channel) {
            BmlMessageChannel.EMAIL -> if (!template.supportsEmail) {
                throw BmlMessageHostException("Message template '$templateKey' does not declare an email channel")
            }
            BmlMessageChannel.PUSH -> if (!template.supportsPush) {
                throw BmlMessageHostException("Message template '$templateKey' does not declare a push channel")
            }
            null -> Unit
        }
        return template.renderMessage(message)
    }

    /** Close the child classloader. Callers drain in-flight renders first (see [BmlMessageProjectHost]). */
    override fun close() {
        loader.close()
    }

    companion object {
        const val MODULE_CLASS: String = "bml.generated.BmlMessages"

        /**
         * Load [jar] in a child classloader parented to [parent] and index its templates by key.
         * Throws [BmlMessageHostException] (with the loader closed) when the jar carries no module,
         * the module type doesn't cross the boundary, or two templates collide on a key.
         */
        fun load(jar: File, parent: ClassLoader): BmlMessageJar {
            if (!jar.isFile) throw BmlMessageHostException("Message jar does not exist: $jar")
            val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), parent)
            try {
                // Initializing the module class runs its generated class initializer, which registers
                // the instance through the parent-shared BmlMessageModules handshake — reflection-free.
                val module = loadModule(jar, loader)
                val templates = LinkedHashMap<String, BmlMessageTemplate>()
                for (template in module.templates) {
                    val previous = templates.put(template.key, template)
                    if (previous != null) {
                        throw BmlMessageHostException("${jar.name} declares duplicate template key '${template.key}'")
                    }
                }
                return BmlMessageJar(jar, loader, templates)
            } catch (e: Throwable) {
                loader.close()
                throw e
            }
        }

        private fun loadModule(jar: File, loader: URLClassLoader): BmlMessageModule {
            val moduleClass = try {
                Class.forName(MODULE_CLASS, true, loader)
            } catch (_: ClassNotFoundException) {
                throw BmlMessageHostException(
                    "${jar.name} carries no $MODULE_CLASS — not a compiled BML message jar",
                )
            }
            val instance = BmlMessageModules.claim()
                ?: moduleClass.instanceOrThrow(jar, MODULE_CLASS)
            return instance as? BmlMessageModule ?: throw BmlMessageHostException(
                "${jar.name}'s $MODULE_CLASS does not implement BmlMessageModule from the host's core-bml — " +
                    "the jar likely bundles its own copy of core-bml (it must depend on it, never embed it)",
            )
        }

        private fun Class<*>.instanceOrThrow(jar: File, moduleClass: String): Any =
            runCatching { getField("INSTANCE").get(null) }.getOrElse { e ->
                throw BmlMessageHostException(
                    "${jar.name}'s $moduleClass registered nothing and exposes no INSTANCE — " +
                        "recompile the message project against a bml with the discovery handshake",
                    e as? Exception,
                )
            }
    }
}

/** A typed message-hosting failure (unknown template, malformed jar, no active version). */
class BmlMessageHostException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
