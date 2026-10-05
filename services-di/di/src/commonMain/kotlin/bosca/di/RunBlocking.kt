package bosca.di

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * A simple runBlocking for KMP.
 * On JVM and Native, it uses the standard runBlocking.
 * On JS and Wasm, it only works if the block does not actually suspend.
 */
expect fun <T> runBlocking(block: suspend () -> T): T

fun <T> runBlockingNoSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) {
        result = it
    })
    return result?.getOrThrow() ?: error("block suspended")
}
