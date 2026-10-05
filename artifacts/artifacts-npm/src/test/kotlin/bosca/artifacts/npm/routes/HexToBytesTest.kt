package bosca.artifacts.npm.routes

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the [hexToBytes] utility that converts hexadecimal
 * strings into their corresponding byte arrays. This function is used
 * when verifying NPM package integrity checksums against stored digests.
 */
class HexToBytesTest {

    /**
     * A well-known ASCII string encoded as hex should round-trip back
     * to the original text when decoded.
     */
    @Test
    fun `hexToBytes converts hex string to correct byte array`() {
        val bytes = hexToBytes("48656c6c6f")
        assertEquals("Hello", bytes.decodeToString())
    }

    /**
     * An empty hex string should produce a zero-length byte array
     * rather than throwing an exception.
     */
    @Test
    fun `hexToBytes handles empty string`() {
        val bytes = hexToBytes("")
        assertEquals(0, bytes.size)
    }

    /**
     * High-value bytes (above 0x7F) must be preserved correctly even
     * though Kotlin bytes are signed.
     */
    @Test
    fun `hexToBytes handles high-value bytes`() {
        val bytes = hexToBytes("ff00")
        assertEquals(2, bytes.size)
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
    }

    /**
     * Uppercase hex digits should be decoded identically to their
     * lowercase equivalents.
     */
    @Test
    fun `hexToBytes handles uppercase hex digits`() {
        val bytes = hexToBytes("FF00AB")
        assertEquals(3, bytes.size)
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0xAB.toByte(), bytes[2])
    }
}
