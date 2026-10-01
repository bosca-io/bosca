package yks.lib0

actual fun randomUint32(): Int =
    (kotlin.math.floor(kotlin.random.Random.nextDouble() * 0xFFFFFFFF.toDouble())).toInt()

actual fun randomUuid(): String {
    // crypto.randomUUID() is available in modern browsers and Node.js
    return js("(typeof crypto !== 'undefined' && crypto.randomUUID) ? crypto.randomUUID() : require('crypto').randomUUID()").unsafeCast<String>()
}

actual fun currentTimeMillis(): Long = js("Date.now()").unsafeCast<Double>().toLong()
