package bosca.ksp.generator.graphql

import bosca.ksp.visitors.FoundTypeController
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DispatchersRegistryGeneratorTest {

    private val output = ByteArrayOutputStream()
    private val codeGenerator = mockk<CodeGenerator>(relaxed = true) {
        every { createNewFile(any(), any(), any(), any()) } returns output
    }
    private val generator = DispatchersRegistryGenerator(codeGenerator, "Test")

    @Before
    fun setUp() {
        output.reset()
    }

    @Test
    fun `controllers for the same GraphQL type are emitted as one composite dispatcher`() {
        generator.generate(
            listOf(
                controller("SharedType", "FirstDispatcher"),
                controller("OtherType", "OtherDispatcher"),
                controller("SharedType", "SecondDispatcher"),
            )
        )

        val code = output.toString(Charsets.UTF_8.name()).replace(Regex("\\s+"), " ")
        assertEquals(1, Regex("\\\"SharedType\\\" to").findAll(code).count())
        assertTrue(
            code.contains(
                "\"SharedType\" to CompositeDispatcher(listOf(FirstDispatcher(get()), SecondDispatcher(get())))"
            )
        )
        assertTrue(code.contains("\"OtherType\" to OtherDispatcher(get())"))
    }

    private fun controller(typeName: String, dispatcherName: String): FoundTypeController {
        val declaration = mockk<KSClassDeclaration>()
        every { declaration.containingFile } returns mockk<KSFile>(relaxed = true)
        return FoundTypeController(
            targetClass = mockk<KSType>(),
            typeName = typeName,
            controller = ClassName("example", dispatcherName.removeSuffix("Dispatcher") + "Controller"),
            controllerProvider = ClassName("example", dispatcherName.removeSuffix("Dispatcher") + "ControllerProvider"),
            dispatcher = ClassName("example", dispatcherName),
            dispatcherProvider = ClassName("example", dispatcherName + "Provider"),
            classDeclaration = declaration,
        )
    }
}
