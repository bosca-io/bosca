package yks.lib0

import kotlin.random.Random
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval

actual fun randomUint32(): Int = Random.nextInt()

actual fun randomUuid(): String {
    val bytes = Random.nextBytes(16)
    // Set version 4 and variant bits
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    return buildString {
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            append(HEX_CHARS[b shr 4])
            append(HEX_CHARS[b and 0x0F])
            if (i == 3 || i == 5 || i == 7 || i == 9) append('-')
        }
    }
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun currentTimeMillis(): Long = memScoped {
    val tv = alloc<timeval>()
    gettimeofday(tv.ptr, null)
    tv.tv_sec * 1000L + tv.tv_usec / 1000L
}
