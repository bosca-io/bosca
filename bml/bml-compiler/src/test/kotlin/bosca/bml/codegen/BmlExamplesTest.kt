package bosca.bml.codegen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The documentation examples and the example site are not part of any build, so this keeps them in
 * step with the compiler: every example must generate without errors or warnings.
 */
class BmlExamplesTest {
    private val bmlRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "docs/examples").isDirectory && File(it, "examples/site").isDirectory }

    @Test
    fun `documentation examples generate cleanly`() = assertGeneratesCleanly(File(bmlRoot, "docs/examples"))

    @Test
    fun `example site generates cleanly`() = assertGeneratesCleanly(File(bmlRoot, "examples/site/src/main/bml"))

    private fun assertGeneratesCleanly(dir: File) {
        val sources = dir.walkTopDown().filter { it.isFile && it.extension == "bml" }.toList()
        assertTrue(sources.isNotEmpty(), "no .bml examples under $dir")
        val out = kotlin.io.path.createTempDirectory("bml-examples").toFile()
        try {
            val result = BmlProjectGenerator("bml.generated").generateAll(dir, out)
            assertEquals(
                emptyList(),
                result.diagnostics.map { "${it.severity} ${it.message} (line ${it.span.startLine})" },
                "examples under ${dir.relativeTo(bmlRoot)} must generate without diagnostics",
            )
            assertEquals(sources.size, result.written.size, "every example should produce a generated source")
        } finally {
            out.deleteRecursively()
        }
    }
}
