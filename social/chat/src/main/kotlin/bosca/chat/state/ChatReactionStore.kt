package bosca.chat.state

import bosca.chat.model.MessageReaction
import bosca.nats.NatsConnectionPool
import bosca.serialization.UUID
import io.nats.client.api.StorageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ChatReactionStore(
    nats: NatsConnectionPool,
) {
    private val accessor = NatsKeyValueAccessor(nats, BUCKET) {
        storageType(StorageType.File)
        description("Chat message reactions: <channelId>.<sequence>.<profileId> -> JSON array of reaction records")
    }

    suspend fun add(
        channelId: UUID,
        sequence: Long,
        profileId: UUID,
        reactionId: UUID,
        emoji: String,
    ): Boolean {
        val kv = accessor.get()
        val k = key(channelId, sequence, profileId)
        return withContext(Dispatchers.IO) {
            val existing = readReactions(kv, k)
            if (existing.any { it.emoji == emoji }) return@withContext false
            kv.put(k, encodeReactions(existing + StoredReaction(reactionId, emoji)))
            true
        }
    }

    suspend fun remove(channelId: UUID, sequence: Long, profileId: UUID, emoji: String) {
        val kv = accessor.get()
        val k = key(channelId, sequence, profileId)
        withContext(Dispatchers.IO) {
            val existing = readReactions(kv, k)
            val updated = existing.filterNot { it.emoji == emoji }
            if (updated.isEmpty()) {
                runCatching { kv.delete(k) }
            } else if (updated.size != existing.size) {
                kv.put(k, encodeReactions(updated))
            }
        }
    }

    suspend fun getForMessage(channelId: UUID, sequence: Long): List<MessageReaction> {
        return getForMessages(channelId, listOf(sequence))[sequence].orEmpty()
    }

    suspend fun getForMessages(
        channelId: UUID,
        sequences: Collection<Long>,
    ): Map<Long, List<MessageReaction>> {
        if (sequences.isEmpty()) return emptyMap()
        val kv = accessor.get()
        val channelPrefix = "$channelId."
        val requested = sequences.toSet()
        return withContext(Dispatchers.IO) {
            val result = mutableMapOf<Long, MutableList<MessageReaction>>()
            requested.chunked(MAX_FILTERS_PER_REQUEST).forEach { batch ->
                val filters = batch.map { "$channelId.$it.>" }
                for (k in kv.keys(filters)) {
                    if (!k.startsWith(channelPrefix)) continue
                    val rest = k.removePrefix(channelPrefix)
                    val dotIdx = rest.indexOf('.')
                    if (dotIdx < 0) continue
                    val seq = rest.substring(0, dotIdx).toLongOrNull()?.takeIf { it in requested } ?: continue
                    val profileId = runCatching { UUID.parse(rest.substring(dotIdx + 1)) }.getOrNull() ?: continue
                    val reactions = readReactions(kv, k)
                    val list = result.getOrPut(seq) { mutableListOf() }
                    for (reaction in reactions) {
                        list.add(
                            MessageReaction(
                                emoji = reaction.emoji,
                                profileId = profileId,
                                id = reaction.id,
                            ),
                        )
                    }
                }
            }
            result
        }
    }

    /** Drops every reaction stored for one message in [channelId]. */
    suspend fun clearMessage(channelId: UUID, sequence: Long) {
        val kv = accessor.get()
        withContext(Dispatchers.IO) {
            kv.keys("$channelId.$sequence.>").forEach(kv::delete)
        }
    }

    /** Drops every reaction stored for [channelId]. */
    suspend fun clearChannel(channelId: UUID) {
        val kv = accessor.get()
        withContext(Dispatchers.IO) {
            kv.keys("$channelId.>").forEach(kv::delete)
        }
    }

    /** Drops every reaction owned by a permanently deleted profile. */
    suspend fun clearProfile(profileId: UUID) {
        val kv = accessor.get()
        withContext(Dispatchers.IO) {
            kv.keys("*.*.$profileId").forEach(kv::delete)
        }
    }

    @Suppress("unused")
    suspend fun bucketName(): String = BUCKET

    private fun key(channelId: UUID, sequence: Long, profileId: UUID): String =
        "$channelId.$sequence.$profileId"

    private fun readReactions(kv: io.nats.client.KeyValue, key: String): List<StoredReaction> {
        val entry = runCatching { kv.get(key) }.getOrNull() ?: return emptyList()
        val bytes = entry.value ?: return emptyList()
        if (bytes.isEmpty()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(String(bytes)).jsonArray.mapNotNull { element ->
                when (element) {
                    is JsonObject -> {
                        val id = element["id"]?.jsonPrimitive?.contentOrNull
                            ?.let { runCatching { UUID.parse(it) }.getOrNull() }
                            ?: return@mapNotNull null
                        val emoji = element["emoji"]?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null
                        StoredReaction(id, emoji)
                    }
                    is JsonPrimitive -> element.contentOrNull?.let { emoji ->
                        StoredReaction(legacyReactionId(key, emoji), emoji)
                    }
                    else -> null
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun encodeReactions(reactions: List<StoredReaction>): ByteArray =
        JsonArray(reactions.map { reaction ->
            buildJsonObject {
                put("id", reaction.id.toString())
                put("emoji", reaction.emoji)
            }
        }).toString().toByteArray()

    private fun legacyReactionId(key: String, emoji: String): UUID = UUID.parse(
        java.util.UUID.nameUUIDFromBytes("bosca.chat.reaction:$key:$emoji".toByteArray()).toString(),
    )

    private data class StoredReaction(val id: UUID, val emoji: String)

    companion object {
        const val BUCKET = "chat-reactions"
        private const val MAX_FILTERS_PER_REQUEST = 100
    }
}
