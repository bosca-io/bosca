package bosca.git.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Loads independent HTTP resources in bounded groups while preserving input order and cancellation. */
internal suspend fun <T, R> Iterable<T>.loadConcurrently(load: suspend (T) -> R): List<R> =
    chunked(4).flatMap { batch -> coroutineScope { batch.map { async { load(it) } }.awaitAll() } }
