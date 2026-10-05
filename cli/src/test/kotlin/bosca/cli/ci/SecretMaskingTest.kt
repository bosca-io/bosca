package bosca.cli.ci

import kotlin.test.Test
import kotlin.test.assertEquals

class SecretMaskingTest {

    @Test
    fun `masks single secret in log line`() {
        val masker = SecretMasker(setOf("super-secret-token"))
        assertEquals("Token: ***", masker.mask("Token: super-secret-token"))
    }

    @Test
    fun `masks multiple secrets in single line`() {
        val masker = SecretMasker(setOf("secret1", "secret2"))
        assertEquals("a=*** b=***", masker.mask("a=secret1 b=secret2"))
    }

    @Test
    fun `masks secret appearing multiple times`() {
        val masker = SecretMasker(setOf("abc"))
        assertEquals("***-***-***", masker.mask("abc-abc-abc"))
    }

    @Test
    fun `does not alter lines without secrets`() {
        val masker = SecretMasker(setOf("secret"))
        assertEquals("normal log line", masker.mask("normal log line"))
    }

    @Test
    fun `handles empty secret set`() {
        val masker = SecretMasker(emptySet())
        assertEquals("nothing to mask", masker.mask("nothing to mask"))
    }

    @Test
    fun `skips empty string secrets`() {
        val masker = SecretMasker(setOf("", "real-secret"))
        assertEquals("value: ***", masker.mask("value: real-secret"))
    }

    @Test
    fun `masks secrets in multiline content`() {
        val masker = SecretMasker(setOf("password123"))
        val input = "line1\nDB_PASS=password123\nline3"
        assertEquals("line1\nDB_PASS=***\nline3", masker.mask(input))
    }
}

class SecretMasker(private val secrets: Set<String>) {
    fun mask(content: String): String {
        var result = content
        for (secret in secrets) {
            if (secret.isNotEmpty()) {
                result = result.replace(secret, "***")
            }
        }
        return result
    }
}
