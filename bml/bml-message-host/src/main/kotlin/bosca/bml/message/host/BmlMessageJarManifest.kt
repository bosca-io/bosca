package bosca.bml.message.host

import bosca.bml.message.BmlMessageArtifacts
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.jar.JarFile

/**
 * The message manifest the compiler embeds in every message jar
 * (`META-INF/bml/message-manifest.json`) — read WITHOUT classloading the jar, so registries and
 * diagnostics can describe an artifact version before (or instead of) activating it.
 */
@Serializable
data class BmlMessageJarManifest(
    val manifestVersion: Int,
    /** The well-known module entry point to instantiate after classloading. */
    val module: String,
    val templates: List<Template> = emptyList(),
) {
    @Serializable
    data class Template(
        val key: String,
        val source: String,
        val objectName: String,
        val supportsEmail: Boolean = true,
        val supportsPush: Boolean = false,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Read the manifest from [jar], or null when the jar carries none (not a message jar).
         * Throws [BmlMessageHostException] on a manifest this host doesn't understand — fail closed
         * rather than activate an artifact whose contract has moved past us.
         */
        fun read(jar: File): BmlMessageJarManifest? {
            JarFile(jar).use { jarFile ->
                val entry = jarFile.getJarEntry(BmlMessageArtifacts.MANIFEST_PATH) ?: return null
                val manifest = jarFile.getInputStream(entry).use { stream ->
                    json.decodeFromString(serializer(), stream.readBytes().decodeToString())
                }
                if (manifest.manifestVersion > BmlMessageArtifacts.MANIFEST_VERSION) {
                    throw BmlMessageHostException(
                        "${jar.name} carries message manifest version ${manifest.manifestVersion}; " +
                            "this host understands <= ${BmlMessageArtifacts.MANIFEST_VERSION}",
                    )
                }
                return manifest
            }
        }
    }
}
