package yks.lib0

actual fun randomUint32(): Int =
    (kotlin.math.floor(kotlin.random.Random.nextDouble() * 0xFFFFFFFF.toDouble())).toInt()

actual fun randomUuid(): String {
    val bytes = ByteArray(16)
    for (i in bytes.indices) {
        bytes[i] = (kotlin.random.Random.nextInt(256) and 0xFF).toByte()
    }
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

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun dateNow(): JsNumber = js("Date.now()")

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
actual fun currentTimeMillis(): Long = dateNow().toDouble().toLong()
