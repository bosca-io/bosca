package bosca.ksp.visitors

import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSTypeReference
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SerializableClassVisitor] feeds the KSP-generated `SerializerRegistrar`, which both populates the
 * runtime `SerializerCache` and tells `BoscaFeature` which classes to register for native-image
 * reflection. The crux of the native fix is that **nested** `@Serializable` types (a Koog tool's
 * `Input`/`Output`) are emitted with the binary `Outer$Inner` name so `Class.forName` can resolve them.
 */
class SerializableClassVisitorTest {

    private val processed = mutableSetOf<Pair<String, String>>()
    private val visitor = SerializableClassVisitor(processed)

    @Before
    fun setup() = mockkStatic("com.google.devtools.ksp.UtilsKt")

    @After
    fun tearDown() = unmockkStatic("com.google.devtools.ksp.UtilsKt")

    private fun mockKSName(value: String): KSName = mockk { every { asString() } returns value }

    private fun mockClass(
        packageName: String,
        simpleName: String,
        parent: KSDeclaration? = null,
        public: Boolean = true,
        typeParameterCount: Int = 0,
        classKind: ClassKind = ClassKind.CLASS,
        supertypes: List<KSClassDeclaration> = emptyList(),
    ): KSClassDeclaration {
        val decl = mockk<KSClassDeclaration>(relaxed = true)
        every { decl.classKind } returns classKind
        every { decl.simpleName } returns mockKSName(simpleName)
        every { decl.qualifiedName } returns mockKSName("$packageName.$simpleName")
        every { decl.parentDeclaration } returns parent
        every { decl.typeParameters } returns List(typeParameterCount) { mockk<KSTypeParameter>(relaxed = true) }
        every { decl.containingFile } returns mockk<KSFile>(relaxed = true) { every { this@mockk.packageName } returns mockKSName(packageName) }
        every { decl.isPublic() } returns public
        every { decl.superTypes } returns supertypes.map { supertype ->
            val type = mockk<KSType> { every { declaration } returns supertype }
            mockk<KSTypeReference> { every { resolve() } returns type }
        }.asSequence()
        return decl
    }

    @Test
    fun `nested public Serializable class is emitted with nested ClassName and binary qualified name`() {
        val outer = mockClass("bosca.ai.kit.tools.content", "CreateDocumentTool")
        val inner = mockClass("bosca.ai.kit.tools.content", "Input", parent = outer)

        visitor.visitClassDeclaration(inner, Unit)
        val found = visitor.consume().single()

        assertEquals(listOf("CreateDocumentTool", "Input"), found.className.simpleNames)
        assertEquals("bosca.ai.kit.tools.content", found.className.packageName)
        // The binary name (with `$`) is what BoscaFeature's Class.forName needs.
        assertEquals("bosca.ai.kit.tools.content.CreateDocumentTool\$Input", found.qualifiedName)
    }

    @Test
    fun `top-level class keeps a plain dotted qualified name`() {
        visitor.visitClassDeclaration(mockClass("bosca.ai.kit.agents", "KitState"), Unit)
        val found = visitor.consume().single()

        assertEquals(listOf("KitState"), found.className.simpleNames)
        assertEquals("bosca.ai.kit.agents.KitState", found.qualifiedName)
    }

    @Test
    fun `domain supertypes are captured transitively while the symbol is valid`() {
        val event = mockClass("bosca.events", "Event", classKind = ClassKind.INTERFACE)
        val profileEvent = mockClass(
            "bosca.profile.events",
            "ProfileEvent",
            classKind = ClassKind.INTERFACE,
            supertypes = listOf(event),
        )
        val added = mockClass(
            "bosca.profile.events",
            "ProfileAdded",
            supertypes = listOf(profileEvent),
        )

        visitor.visitClassDeclaration(added, Unit)

        assertEquals(
            listOf(
                "bosca.profile.events.ProfileEvent",
                "bosca.events.Event",
            ),
            visitor.consume().single().assignableTypes.map { it.canonicalName },
        )
    }

    @Test
    fun `generic Serializable class is skipped (its serializer needs type-arg serializers)`() {
        visitor.visitClassDeclaration(mockClass("bosca.test", "Box", typeParameterCount = 1), Unit)
        assertTrue(visitor.consume().isEmpty())
    }

    @Test
    fun `function-local class is skipped (not addressable by name)`() {
        val function = mockk<KSFunctionDeclaration>(relaxed = true)
        visitor.visitClassDeclaration(mockClass("bosca.test", "Local", parent = function), Unit)
        assertTrue(visitor.consume().isEmpty())
    }

    @Test
    fun `nested class under a non-public outer is skipped`() {
        val outer = mockClass("bosca.test", "InternalOuter", public = false)
        val inner = mockClass("bosca.test", "Input", parent = outer)
        visitor.visitClassDeclaration(inner, Unit)
        assertTrue(visitor.consume().isEmpty())
    }

    @Test
    fun `two tools in the same package each with a nested Input are both emitted`() {
        val toolA = mockClass("bosca.tools", "ToolA")
        val toolB = mockClass("bosca.tools", "ToolB")
        visitor.visitClassDeclaration(mockClass("bosca.tools", "Input", parent = toolA), Unit)
        visitor.visitClassDeclaration(mockClass("bosca.tools", "Input", parent = toolB), Unit)

        val names = visitor.consume().map { it.qualifiedName }.toSet()
        assertEquals(setOf("bosca.tools.ToolA\$Input", "bosca.tools.ToolB\$Input"), names)
    }
}
