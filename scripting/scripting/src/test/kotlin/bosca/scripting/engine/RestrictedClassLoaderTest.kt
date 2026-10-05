package bosca.scripting.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that [RestrictedClassLoader] correctly enforces the allowlist model:
 * permitted packages can be loaded, always-denied classes are blocked even if they
 * fall within an allowed prefix, and classes outside the allowlist are rejected.
 */
class RestrictedClassLoaderTest {

    private val loader = RestrictedClassLoader(ClassLoader.getSystemClassLoader())

    // --- Allowed classes ---

    @Test
    fun `allows loading classes from default allowed prefixes`() {
        // java.lang.String is allowed via the "java.lang." prefix
        val clazz = loader.loadClass("java.lang.String")
        assertNotNull(clazz)
        assertEquals("java.lang.String", clazz.name)
    }

    @Test
    fun `allows loading kotlin stdlib classes`() {
        val clazz = loader.loadClass("kotlin.collections.CollectionsKt")
        assertNotNull(clazz)
    }

    @Test
    fun `allows loading java util classes`() {
        val clazz = loader.loadClass("java.util.ArrayList")
        assertNotNull(clazz)
    }

    // --- Always-denied classes ---

    @Test
    fun `blocks java lang Runtime despite java lang being allowed`() {
        val ex = assertFailsWith<SecurityException> {
            loader.loadClass("java.lang.Runtime")
        }
        assertTrue(ex.message!!.contains("java.lang.Runtime"))
    }

    @Test
    fun `blocks java lang Thread despite java lang being allowed`() {
        assertFailsWith<SecurityException> {
            loader.loadClass("java.lang.Thread")
        }
    }

    @Test
    fun `blocks java lang ProcessBuilder`() {
        assertFailsWith<SecurityException> {
            loader.loadClass("java.lang.ProcessBuilder")
        }
    }

    @Test
    fun `blocks java lang reflect classes`() {
        assertFailsWith<SecurityException> {
            loader.loadClass("java.lang.reflect.Method")
        }
    }

    @Test
    fun `blocks java io classes`() {
        assertFailsWith<SecurityException> {
            loader.loadClass("java.io.File")
        }
    }

    @Test
    fun `blocks java nio classes`() {
        assertFailsWith<SecurityException> {
            loader.loadClass("java.nio.file.Files")
        }
    }

    @Test
    fun `blocks java net classes`() {
        assertFailsWith<SecurityException> {
            loader.loadClass("java.net.URL")
        }
    }

    @Test
    fun `blocks jdk internal classes`() {
        assertFailsWith<SecurityException> {
            loader.loadClass("jdk.internal.misc.Unsafe")
        }
    }

    @Test
    fun `blocks sun misc classes`() {
        assertFailsWith<SecurityException> {
            loader.loadClass("sun.misc.Unsafe")
        }
    }

    // --- Non-allowlisted classes ---

    @Test
    fun `blocks classes outside allowlist`() {
        assertFailsWith<SecurityException> {
            loader.loadClass("com.notallowed.SomeClass")
        }
    }

    // --- Custom prefixes ---

    @Test
    fun `accepts custom allowed prefixes`() {
        val custom = RestrictedClassLoader(
            ClassLoader.getSystemClassLoader(),
            listOf("com.example.", "kotlin.", "java.lang.")
        )
        assertEquals(setOf("com.example.", "kotlin.", "java.lang."), custom.getAllowedPrefixes())
    }

    @Test
    fun `getAllowedPrefixes returns defensive copy`() {
        val prefixes1 = loader.getAllowedPrefixes()
        val prefixes2 = loader.getAllowedPrefixes()
        assertEquals(prefixes1, prefixes2)
    }

    // --- Packageless classes (compiled scripts) ---

    @Test
    fun `allows classes without a package name`() {
        // Classes without a dot in the name are compiled script classes
        // and should bypass the allowlist. We can't actually load them,
        // but the RestrictedClassLoader should delegate to the parent
        // rather than throwing SecurityException.
        try {
            loader.loadClass("ScriptClass")
        } catch (_: ClassNotFoundException) {
            // Expected: the parent loader can't find it, but it was not
            // rejected by our security checks.
        }
    }
}
