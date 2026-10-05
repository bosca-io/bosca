package bosca.bml.sample

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Covers the hand-written bootstrap in [Main.kt]. The branchy logic (port parsing, client-dir resolution)
 * is extracted into pure functions so it's unit-testable without binding a port or running `main`, which
 * blocks on the server. `buildServer` is exercised to cover the wiring that hands the compiler-generated
 * registries to [bosca.bml.server.BmlServer].
 */
class MainTest {

    @Test fun `resolvePort defaults to 9090 when no arg is given`() {
        assertEquals(9090, resolvePort(emptyArray()))
    }

    @Test fun `resolvePort parses a numeric first arg`() {
        assertEquals(8080, resolvePort(arrayOf("8080")))
    }

    @Test fun `resolvePort falls back to 9090 for a non-numeric arg`() {
        assertEquals(9090, resolvePort(arrayOf("not-a-port")))
    }

    @Test fun `resolveClientDir returns null when the path does not exist`() {
        assertNull(resolveClientDir("definitely/not/a/real/dir-${"x".repeat(8)}"))
    }

    @Test fun `resolveClientDir returns the dir when it exists`() {
        // "." is always a directory; exercises the takeIf-non-null branch.
        val dir = resolveClientDir(".")
        assertNotNull(dir, "an existing directory must be returned")
        assertEquals(".", dir.path)
    }

    @Test fun `buildServer wires the generated registries into a BmlServer`() {
        // Constructs (does not start) the server, covering the registry-wiring lines. No port is bound.
        val server = buildServer(emptyArray(), clientDirProp = "definitely/not/real", graphqlEndpoint = null)
        assertNotNull(server, "buildServer must construct a BmlServer")
        // Calling it twice yields independent instances (no accidental singleton/caching).
        val other = buildServer(arrayOf("1234"), clientDirProp = null, graphqlEndpoint = "http://x/graphql")
        assertNotNull(other)
        assert(server !== other) { "each buildServer call must produce a fresh instance" }
    }
}
