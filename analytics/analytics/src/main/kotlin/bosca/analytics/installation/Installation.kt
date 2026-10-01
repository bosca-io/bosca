package bosca.analytics.installation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@Serializable
data class Installation(
    val id: String
) {
    companion object {
        private const val NODE_BITS: Int = 8

        private data class LastCreation(
            var millis: Long = 0,
            val created: MutableSet<String> = ConcurrentHashMap.newKeySet()
        )

        private val lastCreation = LastCreation()
        private val mutex = Mutex()

        @Volatile
        private var cachedNodeId: Int? = null

        private fun getNodeId(): Int {
            return cachedNodeId ?: run {
                val podIp = resolvePodIp(System.getenv("POD_IP"), System.getProperty("POD_IP"))
                val ip = Inet4Address.getByName(podIp)
                val nodeId = ip.address[3].toInt() and 0xFF
                log.info("***** Node ID: $nodeId")
                cachedNodeId = nodeId
                nodeId
            }
        }

        suspend fun new(): Installation = mutex.withLock {
            var id: String?
            while (true) {
                val timestamp = Instant.now().toEpochMilli()
                val timebits = (timestamp and ((1L shl 48) - 1))
                val randomMsb = Random.nextInt(65536) // 0 to 65535 (16-bit)
                val lsb = Random.nextLong()
                val nodeId = getNodeId()
                val clearedMsb = randomMsb and (((1 shl NODE_BITS) - 1) shl (16 - NODE_BITS)).inv()
                val nodeMsb = clearedMsb or (nodeId shl (16 - NODE_BITS))
                val msb = (timebits shl 16) or nodeMsb.toLong()
                id = createUlidString(msb, lsb)
                if (lastCreation.millis != timestamp) {
                    lastCreation.millis = timestamp
                    lastCreation.created.clear()
                    lastCreation.created.add(id)
                } else if (lastCreation.created.contains(id)) {
                    continue
                } else {
                    lastCreation.created.add(id)
                }
                break
            }
            Installation(id = id)
        }

        private fun createUlidString(msb: Long, lsb: Long): String {
            // Simple ULID-like string creation using base32 encoding
            // This is a simplified version - you might want to use a proper ULID library
            val combined = ByteArray(16)
            // Pack MSB (8 bytes)
            for (i in 7 downTo 0) {
                combined[7 - i] = ((msb shr (i * 8)) and 0xFF).toByte()
            }
            // Pack LSB (8 bytes)
            for (i in 7 downTo 0) {
                combined[15 - i] = ((lsb shr (i * 8)) and 0xFF).toByte()
            }
            return encodeBase32(combined)
        }

        private fun encodeBase32(bytes: ByteArray): String {
            val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
            val result = StringBuilder(26) // ULID length
            var bits = 0L
            var bitCount = 0
            for (byte in bytes) {
                bits = (bits shl 8) or (byte.toLong() and 0xFF)
                bitCount += 8
                while (bitCount >= 5) {
                    bitCount -= 5
                    val index = ((bits shr bitCount) and 0x1F).toInt()
                    result.append(alphabet[index])
                }
            }
            // A 128-bit identifier always leaves three bits after the five-bit groups above.
            val index = ((bits shl (5 - bitCount)) and 0x1F).toInt()
            result.append(alphabet[index])
            return result.toString()
        }

        internal fun resolvePodIp(environment: String?, property: String?): String =
            environment ?: property ?: "127.0.0.1"

        private val log = LoggerFactory.getLogger(Installation::class.java)
    }
}
