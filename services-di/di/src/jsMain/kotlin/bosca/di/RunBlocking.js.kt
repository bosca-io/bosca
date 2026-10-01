package bosca.di

actual fun <T> runBlocking(block: suspend () -> T): T = runBlockingNoSuspend(block)
