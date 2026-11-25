package bosca.scripting.engine

import kotlinx.serialization.Serializable

@Serializable
data class ScriptingSecurityConfiguration(
    val allowedPrefixes: List<String> = DEFAULT_ALLOWED_PREFIXES,
    val executionTimeoutSeconds: Long = 600L,
) {
    companion object {

        val DEFAULT_ALLOWED_PREFIXES = listOf(
            "bosca.",
            "kotlin.",
            "kotlinx.",
            "java.lang.",
            "java.util.",
            "java.time.",
            "java.math.",
            "java.text.",
            "org.slf4j.Logger"
        )
    }
}
