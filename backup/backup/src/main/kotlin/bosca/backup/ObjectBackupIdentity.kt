package bosca.backup

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Separates restore identities while preserving the established backup identity encoding. */
internal fun objectBackupIdentity(operation: ObjectBackupOperation, parts: List<String>): String {
    val identity = when (operation) {
        ObjectBackupOperation.BACKUP -> parts
        ObjectBackupOperation.RESTORE -> listOf("restore") + parts
    }
    val encoded = Json.encodeToString(ListSerializer(String.serializer()), identity)
    return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).toHexString()
}
