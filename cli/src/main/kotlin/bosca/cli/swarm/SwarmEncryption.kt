package bosca.cli.swarm

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Encrypts only credential values; all other config JSON remains readable and editable. */
internal object SwarmSecretEncryption {
    private const val marker = "ENC[v1:"
    private const val iterations = 600_000
    private const val saltSize = 16
    private const val nonceSize = 12
    private const val tagBits = 128
    private val random = SecureRandom()
    private val sharedKeys = setOf("postgresAdmin", "meilisearchKey", "s3Access", "s3Secret", "backupPassword")
    private val siteKeys = setOf("database", "warehouse", "nats", "jwt", "securityEncryption", "storageSigner",
        "initialAdmin", "initialSa", "pipeline", "gatewayProxy")
    private val mlKeys = setOf("boscaToken", "artifactsPushToken", "artifactsPullToken")

    fun hasEncryptedSecrets(document: JsonObject): Boolean {
        var found = false
        transform(document) { value, _ ->
            if (value.startsWith("ENC[")) found = true
            null
        }
        return found
    }

    fun encrypt(document: JsonObject, passphrase: CharArray): JsonObject {
        require(passphrase.size >= 12) { "Swarm config passphrase must be at least 12 characters" }
        KeyCache(passphrase).use { keys ->
            val salt = ByteArray(saltSize).also(random::nextBytes)
            return transform(document) { value, path ->
                when {
                    value.isBlank() -> null
                    value.startsWith("ENC[") -> {
                        // Verify the supplied passphrase before retaining an existing encrypted value.
                        decryptValue(value, path, keys)
                        value
                    }
                    else -> encryptValue(value, path, salt, keys.key(salt))
                }
            }
        }
    }

    fun decrypt(document: JsonObject, passphrase: CharArray): JsonObject = KeyCache(passphrase).use { keys ->
        transform(document) { value, path ->
            if (value.startsWith("ENC[")) decryptValue(value, path, keys) else null
        }
    }

    private fun transform(document: JsonObject, change: (String, String) -> String?): JsonObject {
        fun fields(obj: JsonObject, keys: Set<String>, prefix: String): JsonObject = JsonObject(obj.mapValues { (key, element) ->
            if (key !in keys || element !is JsonPrimitive || !element.isString) element else {
                val value = element.content
                change(value, "$prefix.$key")?.let(::JsonPrimitive) ?: element
            }
        })
        fun nested(obj: JsonObject, key: String, keys: Set<String>, prefix: String): JsonObject {
            val child = obj[key] as? JsonObject ?: return obj
            return JsonObject(obj + (key to fields(child, keys, prefix)))
        }

        var root = nested(document, "secrets", sharedKeys, "secrets")
        root = nested(root, "registryAuth", setOf("password"), "registryAuth")
        root = nested(root, "backup", setOf("secretAccessKey"), "backup")
        val sites = root["sites"] as? JsonArray ?: return root
        root = JsonObject(root + ("sites" to JsonArray(sites.map { element ->
            var site = element.jsonObject
            val id = site["id"]?.jsonPrimitive?.contentOrNull ?: error("Site id is missing")
            val prefix = "sites[$id]"
            site = fields(site, setOf("bmlArtifactsToken"), prefix)
            site = nested(site, "secrets", siteKeys, "$prefix.secrets")
            site = nested(site, "ml", mlKeys, "$prefix.ml")
            site = nested(site, "google", setOf("clientSecret"), "$prefix.google")
            nested(site, "rootRegistryAuth", setOf("password"), "$prefix.rootRegistryAuth")
        })))
        return root
    }

    private fun encryptValue(value: String, path: String, salt: ByteArray, key: ByteArray): String {
        val nonce = ByteArray(nonceSize).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagBits, nonce))
        cipher.updateAAD(path.toByteArray(Charsets.UTF_8))
        val encrypted = salt + nonce + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return "$marker${Base64.getEncoder().encodeToString(encrypted)}]"
    }

    private fun decryptValue(value: String, path: String, keys: KeyCache): String {
        require(value.startsWith(marker) && value.endsWith("]")) { "Unsupported encrypted secret at $path" }
        val encoded = value.substring(marker.length, value.length - 1)
        val bytes = try { Base64.getDecoder().decode(encoded) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("Damaged encrypted secret at $path") }
        require(bytes.size >= saltSize + nonceSize + tagBits / 8) { "Truncated encrypted secret at $path" }
        val salt = bytes.copyOfRange(0, saltSize)
        val nonce = bytes.copyOfRange(saltSize, saltSize + nonceSize)
        val key = keys.key(salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagBits, nonce))
            cipher.updateAAD(path.toByteArray(Charsets.UTF_8))
            try {
                cipher.doFinal(bytes, saltSize + nonceSize, bytes.size - saltSize - nonceSize).toString(Charsets.UTF_8)
            } catch (_: AEADBadTagException) {
                throw IllegalArgumentException("Incorrect passphrase or damaged encrypted secret at $path")
            }
        } finally {
            bytes.fill(0)
        }
    }

    private class KeyCache(private val passphrase: CharArray) : AutoCloseable {
        private val keys = mutableMapOf<String, ByteArray>()

        fun key(salt: ByteArray): ByteArray = keys.getOrPut(Base64.getEncoder().encodeToString(salt)) {
            deriveKey(passphrase, salt)
        }

        override fun close() {
            keys.values.forEach { it.fill(0) }
            keys.clear()
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}

/** Prompt without exposing the passphrase in command arguments or terminal history. */
internal fun swarmConfigPassphrase(confirm: Boolean): CharArray {
    System.getenv("BOSCA_SWARM_CONFIG_PASSPHRASE")?.let { return it.toCharArray() }
    val console = System.console() ?: error(
        "No terminal for passphrase prompt; set BOSCA_SWARM_CONFIG_PASSPHRASE in the CLI environment"
    )
    val first = console.readPassword("Swarm config passphrase: ") ?: error("Passphrase entry cancelled")
    if (!confirm) return first
    val second = console.readPassword("Confirm passphrase: ") ?: error("Passphrase confirmation cancelled")
    try { require(first.contentEquals(second)) { "Passphrases do not match" } }
    finally { second.fill('\u0000') }
    return first
}

/** Transform a config atomically; `--output` can preserve the input for an explicit copy. */
internal fun transformSwarmConfig(source: Path, destination: Path, encrypt: Boolean, passphrase: CharArray) {
    require(Files.isRegularFile(source)) { "Configuration not found: $source" }
    require(source == destination || !Files.exists(destination)) { "Output already exists: $destination" }
    val document = swarmJson.parseToJsonElement(Files.readString(source)).jsonObject
    val clear = if (SwarmSecretEncryption.hasEncryptedSecrets(document)) {
        SwarmSecretEncryption.decrypt(document, passphrase)
    } else document
    swarmJson.decodeFromJsonElement(SwarmConfig.serializer(), clear).validate()
    val changed = if (encrypt) SwarmSecretEncryption.encrypt(document, passphrase) else {
        require(SwarmSecretEncryption.hasEncryptedSecrets(document)) { "Configuration has no encrypted secrets" }
        clear
    }
    privateWrite(destination, swarmJson.encodeToString(JsonObject.serializer(), changed) + "\n",
        replaceExisting = source == destination)
}
