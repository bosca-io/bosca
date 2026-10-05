package bosca.collaboration.federation

import bosca.configuration.model.ConfigurationInput
import bosca.configuration.service.ConfigurationService
import bosca.serialization.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid as KotlinUuid

/**
 * Resolves "this Bosca instance's federation peer id" — the value that
 * outbound federation envelopes use as `originPeerId` so receiving peers
 * can attribute the message to a specific source.
 *
 * The id is persisted under `federation.local_peer_id` via
 * [ConfigurationService] so it survives restarts. On first access (e.g.
 * the first federated message ever sent) the provider auto-generates a
 * UUID and writes it back, so a fresh deploy doesn't need an explicit
 * bootstrap step. Operators who want to override the auto-generated id
 * (for example, to keep continuity across a server replacement) can
 * write a value into the configuration directly through the existing
 * Configurations admin surface.
 */
class LocalPeerIdProvider(
    private val configurationService: ConfigurationService,
) {

    private val key = LOCAL_PEER_ID_KEY
    private val mutex = Mutex()

    @Volatile
    private var cached: UUID? = null

    /**
     * Returns the local peer id, generating and persisting one on first
     * access if necessary. Cached in-memory after first read; the cache
     * is invalidated when [override] writes a new value.
     */
    suspend fun get(): UUID {
        cached?.let { return it }
        return mutex.withLock {
            cached?.let { return@withLock it }
            val resolved = readFromStorage() ?: generateAndPersist()
            cached = resolved
            resolved
        }
    }

    /**
     * Overrides the persisted local peer id. Used by operators or tests
     * to pin a specific id (e.g. for cross-cluster continuity after a
     * disaster recovery rebuild). Does not validate that the new id is
     * unused on remote peers — that's a manual coordination concern.
     */
    suspend fun override(newId: UUID) {
        mutex.withLock {
            writeValue(newId)
            cached = newId
        }
    }

    private suspend fun readFromStorage(): UUID? {
        val configuration = configurationService.getByKey(key) ?: return null
        val value = configurationService.getValue(configuration.id) ?: return null
        if (value is JsonNull) return null
        val text = runCatching { value.jsonObject["peerId"]?.jsonPrimitive?.content }.getOrNull()
            ?: return null
        return runCatching { UUID.parse(text) }.getOrNull()
    }

    private suspend fun generateAndPersist(): UUID {
        val newId = KotlinUuid.random()
        writeValue(newId)
        return newId
    }

    private suspend fun writeValue(id: UUID) {
        val payload = buildJsonObject { put("peerId", JsonPrimitive(id.toString())) }
        val existing = configurationService.getByKey(key)
        if (existing != null) {
            configurationService.setValue(existing.id, payload)
        } else {
            configurationService.setConfiguration(
                ConfigurationInput(
                    key = key,
                    description = "This Bosca instance's federation peer id",
                    value = payload,
                    public = false,
                    permissions = emptyList(),
                ),
            )
        }
    }

    companion object {
        const val LOCAL_PEER_ID_KEY = "federation.local_peer_id"
    }
}
