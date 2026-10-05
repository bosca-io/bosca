package bosca.bml.message

/**
 * Artifact contract for hosted BML message projects.
 */
object BmlMessageArtifacts {
    const val TYPE: String = "raw"
    const val NAMESPACE: String = "bml-message"
    const val MANIFEST_PATH: String = "META-INF/bml/message-manifest.json"
    const val MANIFEST_VERSION: Int = 1
    const val ASSETS_RESOURCE_ROOT: String = "bml/public"
}
