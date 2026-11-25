package bosca.cli.harness.egress

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Relays bytes in both directions until either side reaches end of stream, then returns so the
 * caller closes both connections. A failure in either direction propagates to the caller.
 */
internal suspend fun relayTunnel(clientInput: ByteReadChannel, clientOutput: ByteWriteChannel, upstreamInput: ByteReadChannel, upstreamOutput: ByteWriteChannel) =
    coroutineScope {
        val upload = launch { pump(clientInput, upstreamOutput) }
        val download = launch { pump(upstreamInput, clientOutput) }
        select { upload.onJoin {}; download.onJoin {} }
        upload.cancel(); download.cancel()
    }

private suspend fun pump(input: ByteReadChannel, output: ByteWriteChannel) {
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = input.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read > 0) { output.writeFully(buffer, 0, read); output.flush() }
    }
}
