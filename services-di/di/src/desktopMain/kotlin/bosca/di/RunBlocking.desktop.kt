package bosca.di

import kotlinx.coroutines.runBlocking as kotlinxRunBlocking

actual fun <T> runBlocking(block: suspend () -> T): T = kotlinxRunBlocking { block() }
