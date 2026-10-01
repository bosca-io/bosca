package bosca.docs.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceDocsCompilerTest {

    @Test
    fun `SourceDocsCompiler is a singleton object`() {
        val instance = SourceDocsCompiler
        assertTrue(instance is SourceDocsCompiler)
    }

    @Test
    fun `SourceDocsCompiler class has main method`() {
        val mainMethod = SourceDocsCompiler::class.java.getMethod("main", Array<String>::class.java)
        assertTrue(mainMethod != null)
    }

    @Test
    fun `SourceDocsCompiler main method has JvmStatic annotation`() {
        val mainMethod = SourceDocsCompiler::class.java.getMethod("main", Array<String>::class.java)
        val hasJvmStatic = mainMethod.annotations.any {
            it.annotationClass.qualifiedName == "kotlin.jvm.JvmStatic"
        }
        assertTrue(hasJvmStatic, "main method should have @JvmStatic annotation")
    }

    @Test
    fun `SourceDocsCompiler class name matches expected`() {
        assertEquals("SourceDocsCompiler", SourceDocsCompiler::class.simpleName)
    }
}
