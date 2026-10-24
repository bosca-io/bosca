package bosca.backup

import bosca.server.config.ApplicationConfig
import java.util.Base64

/** Checks declared runtime keys without opening storage or proving that they match source ciphertext. */
internal fun checkPostgresBackupKeys(
    manifest: PostgresBackupManifest,
    config: ApplicationConfig,
    environment: (String) -> String? = System::getenv,
) {
    val supported = setOf("security.encryption.key", "PIPELINE_SECRET_KEY")
    val unsupported = manifest.requiredKeys.keys - supported
    require(unsupported.isEmpty()) {
        "Unsupported required restore encryption keys: ${unsupported.sorted().joinToString(", ")}"
    }
    if ("security.encryption.key" in manifest.requiredKeys) {
        checkNotNull(config.propertyOrNull("security.encryption.key")) {
            "Missing required restore encryption key: security.encryption.key"
        }
    }
    if ("PIPELINE_SECRET_KEY" in manifest.requiredKeys) {
        val encoded = checkNotNull(environment("PIPELINE_SECRET_KEY")) {
            "Missing required restore encryption key: PIPELINE_SECRET_KEY"
        }
        val key = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Required restore encryption key PIPELINE_SECRET_KEY must be base64-encoded AES key bytes", error)
        }
        check(key.size in setOf(16, 24, 32)) {
            "Required restore encryption key PIPELINE_SECRET_KEY must contain 16, 24 or 32 decoded AES key bytes"
        }
    }
}
