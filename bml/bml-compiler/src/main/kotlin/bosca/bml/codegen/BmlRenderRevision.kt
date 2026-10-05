package bosca.bml.codegen

import bosca.bml.project.ContentDigest
import java.util.Properties

/** Published compiler identity used to invalidate generated render output across compiler releases. */
internal object BmlCompilerRevision {
    val current: String by lazy {
        val properties = Properties()
        val resource = BmlCompilerRevision::class.java.getResourceAsStream(
            "/bosca/bml/compiler/bml-compiler.properties",
        ) ?: error("Missing bml-compiler.properties")
        resource.use(properties::load)
        properties.getProperty("version")
            ?.takeIf { it.isNotBlank() && !it.contains("\${") }
            ?: error("Missing BML compiler version")
    }
}

/** Hashes every input that can change generated render code for the same BML source. */
internal fun bmlRenderRevision(
    source: String,
    compilerRevision: String = BmlCompilerRevision.current,
): String = ContentDigest.sha256("${compilerRevision.length}:$compilerRevision$source")
