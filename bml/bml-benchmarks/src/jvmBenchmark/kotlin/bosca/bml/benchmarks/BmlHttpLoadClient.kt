package bosca.bml.benchmarks

import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/** One persistent HTTP/1.1 connection for each JMH caller thread. */
@State(Scope.Thread)
open class BmlHttpLoadClient {
    private var port = -1
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private val headerBuffer = ByteArray(16 * 1024)
    private val bodyBuffer = ByteArray(64 * 1024)

    fun exchange(serverPort: Int, request: ByteArray): Int {
        ensureConnected(serverPort)
        val currentOutput = output ?: error("benchmark connection has no output")
        currentOutput.write(request)
        currentOutput.flush()
        return readResponse(input ?: error("benchmark connection has no input"))
    }

    @TearDown
    open fun close() {
        socket?.close()
        socket = null
        input = null
        output = null
        port = -1
    }

    private fun ensureConnected(serverPort: Int) {
        if (socket?.isClosed == false && port == serverPort) return
        close()
        val connected = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort), 5_000)
            soTimeout = 10_000
        }
        socket = connected
        input = BufferedInputStream(connected.getInputStream(), 64 * 1024)
        output = BufferedOutputStream(connected.getOutputStream(), 64 * 1024)
        port = serverPort
    }

    private fun readResponse(currentInput: BufferedInputStream): Int {
        var headerSize = 0
        var delimiter = 0
        while (delimiter < 4) {
            val byte = currentInput.read()
            check(byte >= 0) { "connection closed while reading response headers" }
            check(headerSize < headerBuffer.size) { "response headers exceeded ${headerBuffer.size} bytes" }
            headerBuffer[headerSize++] = byte.toByte()
            delimiter = when {
                delimiter == 0 && byte == '\r'.code -> 1
                delimiter == 1 && byte == '\n'.code -> 2
                delimiter == 2 && byte == '\r'.code -> 3
                delimiter == 3 && byte == '\n'.code -> 4
                byte == '\r'.code -> 1
                else -> 0
            }
        }
        val headers = String(headerBuffer, 0, headerSize, StandardCharsets.US_ASCII)
        check(headers.startsWith("HTTP/1.1 200 ") || headers.startsWith("HTTP/1.0 200 ")) {
            "benchmark request failed: ${headers.lineSequence().firstOrNull()}"
        }
        val contentLength = CONTENT_LENGTH.find(headers)?.groupValues?.get(1)?.toInt()
        if (contentLength != null) {
            drain(currentInput, contentLength)
            return contentLength
        }
        check(CHUNKED.containsMatchIn(headers)) { "response has neither Content-Length nor chunked framing" }
        var total = 0
        while (true) {
            val line = readLine(currentInput)
            val chunkSize = line.substringBefore(';').toInt(16)
            if (chunkSize == 0) {
                while (readLine(currentInput).isNotEmpty()) { /* trailers */ }
                return total
            }
            drain(currentInput, chunkSize)
            check(currentInput.read() == '\r'.code && currentInput.read() == '\n'.code) {
                "malformed chunk terminator"
            }
            total += chunkSize
        }
    }

    private fun readLine(currentInput: BufferedInputStream): String {
        var size = 0
        while (true) {
            val byte = currentInput.read()
            check(byte >= 0) { "connection closed while reading chunked response" }
            if (byte == '\r'.code) {
                check(currentInput.read() == '\n'.code) { "malformed chunked response line" }
                return String(headerBuffer, 0, size, StandardCharsets.US_ASCII)
            }
            check(size < headerBuffer.size) { "chunked response line is too long" }
            headerBuffer[size++] = byte.toByte()
        }
    }

    private fun drain(currentInput: BufferedInputStream, count: Int) {
        var remaining = count
        while (remaining > 0) {
            val read = currentInput.read(bodyBuffer, 0, minOf(remaining, bodyBuffer.size))
            check(read > 0) { "connection closed with $remaining response bytes remaining" }
            remaining -= read
        }
    }

    private companion object {
        val CONTENT_LENGTH = Regex("(?im)^content-length:\\s*(\\d+)")
        val CHUNKED = Regex("(?im)^transfer-encoding:\\s*chunked")
    }
}
