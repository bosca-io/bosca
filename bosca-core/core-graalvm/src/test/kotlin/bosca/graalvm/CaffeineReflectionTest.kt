package bosca.graalvm

import com.github.benmanes.caffeine.cache.Caffeine
import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals

class CaffeineReflectionTest {

    @Test
    fun `registrations cover the strong reference factories in the resolved Caffeine jar`() {
        val factoryPath = Regex("com/github/benmanes/caffeine/cache/(PS|SS)[A-Z]*\\.class")
        val jarFile = File(Caffeine::class.java.protectionDomain.codeSource.location.toURI())
        val expected = JarFile(jarFile).use { jar ->
            jar.entries().asSequence()
                .map { it.name }
                .filter { factoryPath.matches(it) }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .toSet()
        }

        assertEquals(120, expected.size)
        assertEquals(expected, caffeineFactoryClassNames().toSet())
    }
}
