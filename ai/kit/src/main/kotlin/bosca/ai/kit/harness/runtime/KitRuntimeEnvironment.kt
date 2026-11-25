package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitRuntimeSession
import bosca.security.encryption.EncryptionService
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.Base64

/** Reads the generation's existing encrypted environment without persisting a plaintext copy. */
internal suspend fun runtimeEnvironment(session: KitRuntimeSession, encryption: EncryptionService, json: Json): Map<String, String> {
    val secret = json.decodeFromJsonElement(KitRuntimeSecrets.serializer(), requireNotNull(session.secrets))
    val bytes = encryption.decrypt(EncryptionService.Encrypted(Base64.getDecoder().decode(secret.nonce), Base64.getDecoder().decode(secret.ciphertext)), secret.keyId)
    return json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), bytes.decodeToString())
}
