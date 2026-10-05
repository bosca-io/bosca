package bosca.ksp.generator.route

import bosca.ksp.visitors.FoundRouteController
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSValueArgument
import com.squareup.kotlinpoet.ClassName
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import kotlin.test.assertTrue
import org.junit.Test

class RoutesGeneratorTest {
    @Test
    fun `version listing is registered before the overlapping model download route`() {
        val output = ByteArrayOutputStream()
        val codeGenerator = mockk<CodeGenerator>(relaxed = true) {
            every { createNewFile(any(), any(), any(), any()) } returns output
        }
        RoutesGenerator(codeGenerator, "Test").generate(listOf(
            controller("Download", "/ml/{namespace}/{name}/{version}"),
            controller("ListVersions", "/ml/{namespace}/api/{name}"),
            controller("CatchAll", "/ml/{namespace}/{path...}"),
        ))
        val code = output.toString(Charsets.UTF_8.name())
        val listing = code.indexOf("get(\"/ml/{namespace}/api/{name}\")")
        val download = code.indexOf("get(\"/ml/{namespace}/{name}/{version}\")")
        val catchAll = code.indexOf("get(\"/ml/{namespace}/{path...}\")")
        assertTrue(listing >= 0 && listing < download, code)
        assertTrue(download < catchAll, code)
        assertTrue(code.contains("authenticate(*authenticationProviders.providers, optional = true)"), code)
    }

    private fun controller(name: String, path: String): FoundRouteController {
        fun argument(key: String, argumentValue: Any): KSValueArgument = mockk {
            every { this@mockk.name?.asString() } returns key
            every { value } returns argumentValue
        }
        fun enumValue(value: String): KSClassDeclaration = mockk {
            every { simpleName.asString() } returns value
        }
        val annotation = mockk<KSAnnotation> {
            every { shortName.asString() } returns "RouteController"
            every { arguments } returns listOf(
                argument("path", path), argument("method", enumValue("GET")),
                argument("authentication", enumValue("OPTIONAL")),
            )
        }
        val declaration = mockk<KSClassDeclaration> {
            every { annotations } answers { sequenceOf(annotation) }
            every { superTypes } answers { emptySequence() }
        }
        return FoundRouteController(ClassName("example", name), ClassName("example", name + "Provider"), declaration)
    }
}
