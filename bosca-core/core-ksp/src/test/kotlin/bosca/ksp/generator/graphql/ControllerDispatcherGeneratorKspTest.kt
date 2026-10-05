package bosca.ksp.generator.graphql

import bosca.ksp.Types
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import bosca.ksp.visitors.FoundTypeController
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueArgument
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import java.io.ByteArrayOutputStream
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
import bosca.graphql.server.TypeRuntimeWiring
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for KSP-dependent methods in [ControllerDispatcherGenerator] that require
 * mocked KSP symbol types (KSFunctionDeclaration, KSClassDeclaration, etc.).
 */
class ControllerDispatcherGeneratorKspTest {

    private val codeGenerator = mockk<CodeGenerator>(relaxed = true)
    private val logger = mockk<KSPLogger>(relaxed = true)
    private val generator = ControllerDispatcherGenerator(codeGenerator, logger)

    // ── Helpers ──

    private fun mockKSName(value: String): KSName {
        val name = mockk<KSName>()
        every { name.asString() } returns value
        every { name.getShortName() } returns value.substringAfterLast('.')
        every { name.getQualifier() } returns value.substringBeforeLast('.', "")
        return name
    }

    private fun mockFieldAnnotation(fieldName: String = ""): KSAnnotation {
        val annotation = mockk<KSAnnotation>()
        every { annotation.shortName } returns mockKSName("Field")

        val nameArg = mockk<KSValueArgument>()
        every { nameArg.name } returns mockKSName("name")
        every { nameArg.value } returns fieldName

        val typeArg = mockk<KSValueArgument>()
        every { typeArg.name } returns mockKSName("type")
        every { typeArg.value } returns ""

        every { annotation.arguments } returns listOf(nameArg, typeArg)
        return annotation
    }

    private fun mockFunction(name: String, annotations: List<KSAnnotation> = emptyList(), parameters: List<KSValueParameter> = emptyList()): KSFunctionDeclaration {
        val function = mockk<KSFunctionDeclaration>()
        every { function.simpleName } returns mockKSName(name)
        every { function.annotations } returns annotations.asSequence()
        every { function.parameters } returns parameters
        return function
    }

    /**
     * Creates a mock [KSValueParameter] whose resolved type's declaration has the
     * given [qualifiedName], enabling [getBatchType] to match against [Types.Batch].
     */
    private fun mockParameterWithQualifiedDeclaration(paramName: String, qualifiedName: String): KSValueParameter {
        val param = mockk<KSValueParameter>()
        val typeRef = mockk<KSTypeReference>()
        val ksType = mockk<KSType>()
        val decl = mockk<KSClassDeclaration>()

        every { param.name } returns mockKSName(paramName)
        every { param.type } returns typeRef
        every { typeRef.resolve() } returns ksType
        every { ksType.declaration } returns decl
        every { decl.qualifiedName } returns mockKSName(qualifiedName)

        return param
    }

    // ── appendSource ──

    @Test
    fun `appendSource generates sourceAs call with correct format`() {
        val sb = StringBuilder()
        val args = mutableListOf<Any>()
        val type = ClassName("com.example", "Content")
        with(generator) {
            sb.appendSource("content", type, args)
        }
        assertEquals("val %L = environment.sourceAs<%T>()\n", sb.toString())
    }

    @Test
    fun `appendSource adds parameterName and type to args`() {
        val sb = StringBuilder()
        val args = mutableListOf<Any>()
        val type = ClassName("com.example", "Content")
        with(generator) {
            sb.appendSource("src", type, args)
        }
        assertEquals(2, args.size)
        assertEquals("src", args[0])
        assertEquals(type, args[1])
    }

    @Test
    fun `appendSource passes parameterized type unchanged`() {
        val sb = StringBuilder()
        val args = mutableListOf<Any>()
        val inner = ClassName("com.example", "Content")
        val paramType = ClassName("kotlin.collections", "List").parameterizedBy(inner)
        with(generator) {
            sb.appendSource("items", paramType, args)
        }
        assertEquals(paramType, args[1])
    }

    @Test
    fun `appendSource placeholder alignment`() {
        val sb = StringBuilder()
        val args = mutableListOf<Any>()
        with(generator) {
            sb.appendSource("x", ClassName("a", "B"), args)
        }
        val placeholders = Regex("%[LTS]").findAll(sb.toString()).count()
        assertEquals("Placeholder count must match args", placeholders, args.size)
    }

    // ── getBatchType ──

    @Test
    fun `getBatchType returns null for empty parameters`() {
        val function = mockFunction("doSomething")
        assertNull(generator.getBatchType(function))
    }

    @Test
    fun `getBatchType returns null when no parameter has Batch type`() {
        val param = mockParameterWithQualifiedDeclaration("items", "kotlin.collections.List")
        val function = mockFunction("doSomething", parameters = listOf(param))
        assertNull(generator.getBatchType(function))
    }

    @Test
    fun `getBatchType returns parameter when Batch type found`() {
        val batchParam = mockParameterWithQualifiedDeclaration("batch", Types.Batch.canonicalName)
        val function = mockFunction("doSomething", parameters = listOf(batchParam))
        val result = generator.getBatchType(function)
        assertEquals(batchParam, result)
    }

    @Test
    fun `getBatchType returns Batch parameter among multiple params`() {
        val regularParam = mockParameterWithQualifiedDeclaration("name", "kotlin.String")
        val batchParam = mockParameterWithQualifiedDeclaration("batch", Types.Batch.canonicalName)
        val function = mockFunction("doSomething", parameters = listOf(regularParam, batchParam))
        val result = generator.getBatchType(function)
        assertEquals(batchParam, result)
    }

    @Test
    fun `getBatchType skips non-KSClassDeclaration declarations`() {
        val param = mockk<KSValueParameter>()
        val typeRef = mockk<KSTypeReference>()
        val ksType = mockk<KSType>()
        // Declaration is a KSDeclaration (not KSClassDeclaration)
        val decl = mockk<com.google.devtools.ksp.symbol.KSDeclaration>()

        every { param.name } returns mockKSName("p")
        every { param.type } returns typeRef
        every { typeRef.resolve() } returns ksType
        every { ksType.declaration } returns decl

        val function = mockFunction("fn", parameters = listOf(param))
        assertNull(generator.getBatchType(function))
    }

    // ── newTypeProperty ──

    @Test
    fun `newTypeProperty with single Field function`() {
        val fieldAnnotation = mockFieldAnnotation()
        val fn = mockFunction("getTitle", annotations = listOf(fieldAnnotation))

        val classDecl = mockk<KSClassDeclaration>()
        every { classDecl.getAllFunctions() } returns sequenceOf(fn)

        val prop = generator.newTypeProperty(classDecl, "Content")
        assertEquals("type", prop.name)
        assertEquals(TypeRuntimeWiring::class.qualifiedName, prop.type.toString())
        val code = prop.initializer.toString()
        assertTrue("Should wire typeName", code.contains("newTypeWiring"))
        assertTrue("Should wire field", code.contains(".field"))
        assertTrue("Should end with build()", code.contains(".build()"))
    }

    @Test
    fun `newTypeProperty with custom field name from annotation`() {
        val fieldAnnotation = mockFieldAnnotation("customFieldName")
        val fn = mockFunction("getTitle", annotations = listOf(fieldAnnotation))

        val classDecl = mockk<KSClassDeclaration>()
        every { classDecl.getAllFunctions() } returns sequenceOf(fn)

        val prop = generator.newTypeProperty(classDecl, "Content")
        val code = prop.initializer.toString()
        // The custom name should appear as the first %S arg after newTypeWiring
        assertTrue("Should reference the function name", code.contains("getTitleDataFetcher"))
    }

    @Test
    fun `newTypeProperty with default field name uses function name`() {
        // Empty name means use function simpleName
        val fieldAnnotation = mockFieldAnnotation("")
        val fn = mockFunction("description", annotations = listOf(fieldAnnotation))

        val classDecl = mockk<KSClassDeclaration>()
        every { classDecl.getAllFunctions() } returns sequenceOf(fn)

        val prop = generator.newTypeProperty(classDecl, "MyType")
        val code = prop.initializer.toString()
        assertTrue("Should reference descriptionDataFetcher", code.contains("descriptionDataFetcher"))
    }

    @Test
    fun `newTypeProperty skips functions without Field annotation`() {
        val fieldAnnotation = mockFieldAnnotation()
        val fieldFn = mockFunction("getTitle", annotations = listOf(fieldAnnotation))
        val nonFieldFn = mockFunction("helperMethod", annotations = emptyList())

        val classDecl = mockk<KSClassDeclaration>()
        every { classDecl.getAllFunctions() } returns sequenceOf(fieldFn, nonFieldFn)

        val prop = generator.newTypeProperty(classDecl, "Content")
        val code = prop.initializer.toString()
        assertTrue("Should have getTitle fetcher", code.contains("getTitleDataFetcher"))
        // helperMethod should NOT appear since it has no @Field
        assertTrue("Should NOT have helperMethod", !code.contains("helperMethodDataFetcher"))
    }

    @Test
    fun `newTypeProperty with multiple Field functions`() {
        val fn1 = mockFunction("title", annotations = listOf(mockFieldAnnotation()))
        val fn2 = mockFunction("description", annotations = listOf(mockFieldAnnotation()))
        val fn3 = mockFunction("author", annotations = listOf(mockFieldAnnotation()))

        val classDecl = mockk<KSClassDeclaration>()
        every { classDecl.getAllFunctions() } returns sequenceOf(fn1, fn2, fn3)

        val prop = generator.newTypeProperty(classDecl, "Content")
        val code = prop.initializer.toString()
        assertTrue(code.contains("titleDataFetcher"))
        assertTrue(code.contains("descriptionDataFetcher"))
        assertTrue(code.contains("authorDataFetcher"))
    }

    @Test
    fun `newTypeProperty with no Field functions produces minimal wiring`() {
        val classDecl = mockk<KSClassDeclaration>()
        every { classDecl.getAllFunctions() } returns emptySequence()

        val prop = generator.newTypeProperty(classDecl, "EmptyType")
        val code = prop.initializer.toString()
        assertTrue("Should start with newTypeWiring", code.contains("newTypeWiring"))
        assertTrue("Should end with build()", code.contains(".build()"))
        // No field calls
        assertTrue("Should have no field", !code.contains(".field"))
    }

    @Test
    fun `newTypeProperty with missing name annotation arg falls back to function name`() {
        // Annotation has no "name" arg at all — should use function simpleName
        val annotation = mockk<KSAnnotation>()
        every { annotation.shortName } returns mockKSName("Field")
        // Only "type" arg, no "name" arg
        val typeArg = mockk<KSValueArgument>()
        every { typeArg.name } returns mockKSName("type")
        every { typeArg.value } returns ""
        every { annotation.arguments } returns listOf(typeArg)

        val fn = mockFunction("myField", annotations = listOf(annotation))

        val classDecl = mockk<KSClassDeclaration>()
        every { classDecl.getAllFunctions() } returns sequenceOf(fn)

        val prop = generator.newTypeProperty(classDecl, "TestType")
        val code = prop.initializer.toString()
        assertTrue("Should use function name as fallback", code.contains("myFieldDataFetcher"))
    }

    // ── appendControllerFunction ──

    /**
     * Creates a mock [KSValueParameter] whose resolved type returns [typeName] from
     * the mocked [toTypeName] extension. Requires [mockkStatic] to be active for
     * `com.squareup.kotlinpoet.ksp.KsTypesKt`.
     */
    private fun mockTypedParameter(paramName: String, typeName: com.squareup.kotlinpoet.TypeName, nullable: Boolean = false): KSValueParameter {
        val param = mockk<KSValueParameter>()
        val typeRef = mockk<KSTypeReference>()
        val ksType = mockk<KSType>()

        every { param.name } returns mockKSName(paramName)
        every { param.type } returns typeRef
        every { typeRef.resolve() } returns ksType
        every { ksType.toTypeName(any()) } returns typeName
        every { ksType.toTypeName() } returns typeName
        every { ksType.isMarkedNullable } returns nullable

        return param
    }

    @Test
    fun `appendControllerFunction with no parameters`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        try {
            val fn = mockFunction("doAction")
            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) { sb.appendControllerFunction(fn, args) }

            val code = sb.toString()
            assertTrue("Should call controller function", code.contains("controller.%L("))
            assertTrue("Should end with result", code.contains("result\n"))
            assertEquals(1, args.size)
            assertEquals("doAction", args[0])
        } finally {
            unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        }
    }

    @Test
    fun `appendControllerFunction with ResolverContext parameter`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        try {
            val envType = Types.ResolverContext
            val envParam = mockTypedParameter("environment", envType)
            val fn = mockFunction("doAction", parameters = listOf(envParam))

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) { sb.appendControllerFunction(fn, args) }

            val code = sb.toString()
            assertTrue("Should pass environment", code.contains("%L = environment"))
            // args: function name + param name
            assertEquals(2, args.size)
        } finally {
            unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        }
    }

    @Test
    fun `appendControllerFunction with ApplicationCall parameter`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        try {
            val callParam = mockTypedParameter("call", Types.ServerCall)
            val fn = mockFunction("doAction", parameters = listOf(callParam))

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) { sb.appendControllerFunction(fn, args) }

            val code = sb.toString()
            assertTrue("Should pass call", code.contains("%L = call"))
            assertEquals(2, args.size)
        } finally {
            unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        }
    }

    @Test
    fun `appendControllerFunction with AuthenticationContext parameter`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        try {
            val authParam = mockTypedParameter("auth", Types.AuthenticationContext)
            val fn = mockFunction("doAction", parameters = listOf(authParam))

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) { sb.appendControllerFunction(fn, args) }

            val code = sb.toString()
            assertTrue("Should pass authenticationContext", code.contains("%L = authenticationContext"))
            assertEquals(2, args.size)
        } finally {
            unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        }
    }

    @Test
    fun `appendControllerFunction with non-nullable regular parameter`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        try {
            val param = mockTypedParameter("id", ClassName("kotlin", "String"), nullable = false)
            val fn = mockFunction("getItem", parameters = listOf(param))

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) { sb.appendControllerFunction(fn, args) }

            val code = sb.toString()
            assertTrue("Should have error guard", code.contains("error(\"%L is required\")"))
            // args: functionName, paramName (for %L=), paramName (error msg), paramName (val), paramName (val)
            assertEquals(4, args.size)
        } finally {
            unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        }
    }

    @Test
    fun `appendControllerFunction with nullable regular parameter`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        try {
            val param = mockTypedParameter("name", ClassName("kotlin", "String").copy(nullable = true), nullable = true)
            val fn = mockFunction("search", parameters = listOf(param))

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) { sb.appendControllerFunction(fn, args) }

            val code = sb.toString()
            assertTrue("Should NOT have error guard for nullable", !code.contains("error("))
            // args: functionName, paramName (%L=), paramName (%L val)
            assertEquals(3, args.size)
        } finally {
            unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        }
    }

    @Test
    fun `appendControllerFunction with mixed parameters adds commas`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        try {
            val envType = Types.ResolverContext
            val envParam = mockTypedParameter("env", envType)
            val authParam = mockTypedParameter("auth", Types.AuthenticationContext)
            val idParam = mockTypedParameter("id", ClassName("kotlin", "String"), nullable = false)
            val fn = mockFunction("getItem", parameters = listOf(envParam, authParam, idParam))

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) { sb.appendControllerFunction(fn, args) }

            val code = sb.toString()
            // Should have commas between parameters
            assertTrue("Should have commas", code.contains(", "))
            assertTrue("Should pass environment", code.contains("environment"))
            assertTrue("Should pass authenticationContext", code.contains("authenticationContext"))
            assertTrue("Should have error guard for id", code.contains("error("))
        } finally {
            unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        }
    }

    @Test
    fun `appendControllerFunction with parameterized non-source type`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        try {
            val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
            val param = mockTypedParameter("names", listType, nullable = false)
            val fn = mockFunction("doAction", parameters = listOf(param))

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) { sb.appendControllerFunction(fn, args) }

            val code = sb.toString()
            // Parameterized type that doesn't match source should be treated as regular arg
            assertTrue("Should have error guard", code.contains("error(\"%L is required\")"))
        } finally {
            unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        }
    }

    @Test
    fun `appendControllerFunction special param as last has no trailing comma`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        try {
            val idParam = mockTypedParameter("id", ClassName("kotlin", "String"), nullable = false)
            val envType = Types.ResolverContext
            val envParam = mockTypedParameter("env", envType)
            val fn = mockFunction("doAction", parameters = listOf(idParam, envParam))

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) { sb.appendControllerFunction(fn, args) }

            val code = sb.toString()
            // env is last param, should NOT have trailing comma after "environment"
            assertTrue("Should end with environment)", code.contains("environment)"))
        } finally {
            unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        }
    }

    @Test
    fun `appendControllerFunction ApplicationCall as last param has no trailing comma`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        try {
            val idParam = mockTypedParameter("id", ClassName("kotlin", "String"), nullable = false)
            val callParam = mockTypedParameter("call", Types.ServerCall)
            val fn = mockFunction("doAction", parameters = listOf(idParam, callParam))

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) { sb.appendControllerFunction(fn, args) }

            val code = sb.toString()
            assertTrue("Should end with call)", code.contains("= call)"))
        } finally {
            unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        }
    }

    @Test
    fun `appendControllerFunction AuthenticationContext as last param has no trailing comma`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        try {
            val idParam = mockTypedParameter("id", ClassName("kotlin", "String"), nullable = false)
            val authParam = mockTypedParameter("auth", Types.AuthenticationContext)
            val fn = mockFunction("doAction", parameters = listOf(idParam, authParam))

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) { sb.appendControllerFunction(fn, args) }

            val code = sb.toString()
            assertTrue("Should end with authenticationContext)", code.contains("= authenticationContext)"))
        } finally {
            unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        }
    }

    @Test
    fun `appendControllerFunction regular non-nullable param as last has no trailing comma`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        try {
            val envType = Types.ResolverContext
            val envParam = mockTypedParameter("env", envType)
            val idParam = mockTypedParameter("id", ClassName("kotlin", "String"), nullable = false)
            val fn = mockFunction("doAction", parameters = listOf(envParam, idParam))

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) { sb.appendControllerFunction(fn, args) }

            val code = sb.toString()
            // id is last, should end with the error guard followed by closing paren
            assertTrue("Should have error guard for last param without trailing comma",
                code.contains("is required\")"))
        } finally {
            unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        }
    }

    // ── newFieldProperty ──

    /**
     * Sets up mockkStatic for both kotlinpoet-ksp extension files, creates a mock
     * [KSType] representing the target class, and returns it. Call [cleanupFieldPropertyMocks]
     * in a finally block.
     */
    private fun setupFieldPropertyMocks(): KSType {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")

        val targetClass = mockk<KSType>()
        val targetClassName = ClassName("com.example", "Content")
        every { targetClass.toClassName() } returns targetClassName
        return targetClass
    }

    private fun cleanupFieldPropertyMocks() {
        unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        unmockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
    }

    @Test
    fun `newFieldProperty with source parameter generates sourceAs`() {
        val targetClass = setupFieldPropertyMocks()
        try {
            val sourceTypeName = ClassName("com.example", "Content")
            val sourceParam = mockTypedParameter("content", sourceTypeName)

            val fn = mockFunction("getTitle", parameters = listOf(sourceParam))
            every { fn.modifiers } returns emptySet()

            val type = Types.SuspendDataFetcher.parameterizedBy(ClassName("kotlin", "String"))
            val prop = generator.newFieldProperty(fn, targetClass, type, null)

            assertEquals("getTitleDataFetcher", prop.name)
            val code = prop.initializer.toString()
            assertTrue("Should contain sourceAs for source param", code.contains("sourceAs"))
            assertTrue("Should call controller function", code.contains("controller."))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `newFieldProperty with ResolverContext skips it in variable extraction`() {
        val targetClass = setupFieldPropertyMocks()
        try {
            val envType = Types.ResolverContext
            val envParam = mockTypedParameter("env", envType)

            val fn = mockFunction("getTitle", parameters = listOf(envParam))
            every { fn.modifiers } returns emptySet()

            val type = Types.SuspendDataFetcher.parameterizedBy(ClassName("kotlin", "String"))
            val prop = generator.newFieldProperty(fn, targetClass, type, null)

            val code = prop.initializer.toString()
            // ResolverContext is skipped in variable extraction, only used in controller call
            assertTrue("Should NOT contain sourceAs or getArgument for env",
                !code.contains("sourceAs") || code.contains("environment"))
            assertTrue("Should call controller", code.contains("controller."))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `newFieldProperty with ApplicationCall parameter extracts call from context`() {
        val targetClass = setupFieldPropertyMocks()
        try {
            val callParam = mockTypedParameter("call", Types.ServerCall)

            val fn = mockFunction("doAction", parameters = listOf(callParam))
            every { fn.modifiers } returns emptySet()

            val type = Types.SuspendDataFetcher.parameterizedBy(ClassName("kotlin", "String"))
            val prop = generator.newFieldProperty(fn, targetClass, type, null)

            val code = prop.initializer.toString()
            assertTrue("Should get call from native context", code.contains("environment.context.getAs"))
            assertTrue("Should reference ServerCall", code.contains("ServerCall"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `newFieldProperty with primitive parameter uses getArgument`() {
        val targetClass = setupFieldPropertyMocks()
        try {
            val idParam = mockTypedParameter("id", ClassName("kotlin", "String"), nullable = false)

            val fn = mockFunction("getItem", parameters = listOf(idParam))
            every { fn.modifiers } returns emptySet()

            val type = Types.SuspendDataFetcher.parameterizedBy(ClassName("kotlin", "String"))
            val prop = generator.newFieldProperty(fn, targetClass, type, null)

            val code = prop.initializer.toString()
            assertTrue("Should use getArgument for primitives", code.contains("getArgument"))
            assertTrue("Should call controller", code.contains("controller."))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `newFieldProperty with complex type parameter uses decodeFromJsonElement`() {
        val targetClass = setupFieldPropertyMocks()
        try {
            val inputType = ClassName("com.example", "MyInput")
            val inputParam = mockTypedParameter("input", inputType, nullable = false)

            val fn = mockFunction("createItem", parameters = listOf(inputParam))
            every { fn.modifiers } returns emptySet()

            val type = Types.SuspendDataFetcher.parameterizedBy(ClassName("kotlin", "String"))
            val prop = generator.newFieldProperty(fn, targetClass, type, null)

            val code = prop.initializer.toString()
            assertTrue("Should use decodeFromJsonElement", code.contains("decodeFromJsonElement"))
            assertTrue("Should use anyToJsonElement", code.contains("anyToJsonElement"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `newFieldProperty with AuthenticationContext parameter`() {
        val targetClass = setupFieldPropertyMocks()
        try {
            val authParam = mockTypedParameter("auth", Types.AuthenticationContext)

            val fn = mockFunction("getSecret", parameters = listOf(authParam))
            every { fn.modifiers } returns emptySet()

            val type = Types.SuspendDataFetcher.parameterizedBy(ClassName("kotlin", "String"))
            val prop = generator.newFieldProperty(fn, targetClass, type, null)

            val code = prop.initializer.toString()
            // AuthenticationContext should not generate getSource or getArgument
            assertTrue("Should NOT extract auth via sourceAs", !code.contains("sourceAs"))
            assertTrue("Should call controller", code.contains("controller."))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `newFieldProperty with parameterized source type generates sourceAs`() {
        val targetClass = setupFieldPropertyMocks()
        try {
            // ParameterizedTypeName that matches targetClass
            val sourceType = ClassName("com.example", "Content").parameterizedBy(ClassName("kotlin", "String"))
            val sourceParam = mockTypedParameter("content", sourceType)
            // Override targetClass.toClassName to match the raw type
            every { targetClass.toClassName() } returns ClassName("com.example", "Content")

            val fn = mockFunction("getField", parameters = listOf(sourceParam))
            every { fn.modifiers } returns emptySet()

            val type = Types.SuspendDataFetcher.parameterizedBy(ClassName("kotlin", "String"))
            val prop = generator.newFieldProperty(fn, targetClass, type, null)

            val code = prop.initializer.toString()
            assertTrue("Should generate sourceAs for matching parameterized type", code.contains("sourceAs"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `newFieldProperty with mixed parameters generates all extraction code`() {
        val targetClass = setupFieldPropertyMocks()
        try {
            val envType = Types.ResolverContext
            val envParam = mockTypedParameter("env", envType)
            val authParam = mockTypedParameter("auth", Types.AuthenticationContext)
            val idParam = mockTypedParameter("id", ClassName("kotlin", "String"), nullable = false)
            val nameParam = mockTypedParameter("name", ClassName("kotlin", "String").copy(nullable = true), nullable = true)

            val fn = mockFunction("update", parameters = listOf(envParam, authParam, idParam, nameParam))
            every { fn.modifiers } returns emptySet()

            val type = Types.SuspendDataFetcher.parameterizedBy(ClassName("kotlin", "String"))
            val prop = generator.newFieldProperty(fn, targetClass, type, null)

            val code = prop.initializer.toString()
            assertTrue("Should have getArgument for id", code.contains("getArgument"))
            assertTrue("Should call controller", code.contains("controller."))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `newFieldProperty with ParameterizedTypeName non-source param calls appendArgument`() {
        val targetClass = setupFieldPropertyMocks()
        try {
            // List<String> parameter — ParameterizedTypeName that does NOT match targetClass
            val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
            val listParam = mockTypedParameter("names", listType, nullable = false)

            val fn = mockFunction("setNames", parameters = listOf(listParam))
            every { fn.modifiers } returns emptySet()

            val type = Types.SuspendDataFetcher.parameterizedBy(ClassName("kotlin", "String"))
            val prop = generator.newFieldProperty(fn, targetClass, type, null)

            val code = prop.initializer.toString()
            // ParameterizedTypeName goes through appendArgument (complex branch with ListSerializer)
            assertTrue("Should use decodeFromJsonElement for List param", code.contains("decodeFromJsonElement"))
            assertTrue("Should use ListSerializer", code.contains("ListSerializer"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `newFieldProperty with ClassName source param generates sourceAs`() {
        val targetClass = setupFieldPropertyMocks()
        try {
            // ClassName that matches targetClass (not ParameterizedTypeName)
            val sourceType = ClassName("com.example", "Content")
            val sourceParam = mockTypedParameter("content", sourceType)

            val fn = mockFunction("getTitle", parameters = listOf(sourceParam))
            every { fn.modifiers } returns emptySet()

            val type = Types.SuspendDataFetcher.parameterizedBy(ClassName("kotlin", "String"))
            val prop = generator.newFieldProperty(fn, targetClass, type, null)

            val code = prop.initializer.toString()
            assertTrue("Should use sourceAs for ClassName matching target", code.contains("sourceAs"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `newFieldProperty with batch parameter delegates to appendBatchFunction`() {
        val targetClass = setupFieldPropertyMocks()
        try {
            // BatchKey annotation on targetClass
            val batchKeyAnnotation = mockk<KSAnnotation>()
            every { batchKeyAnnotation.shortName } returns mockKSName("BatchKey")
            val propertyArg = mockk<KSValueArgument>()
            every { propertyArg.name } returns mockKSName("property")
            every { propertyArg.value } returns "id"
            every { batchKeyAnnotation.arguments } returns listOf(propertyArg)
            val targetDecl = mockk<KSDeclaration>()
            every { targetClass.declaration } returns targetDecl
            every { targetDecl.annotations } returns sequenceOf(batchKeyAnnotation)
            every { targetDecl.qualifiedName } returns mockKSName("com.example.Content")
            every { targetClass.toTypeName(any()) } returns ClassName("com.example", "Content")
            every { targetClass.toTypeName() } returns ClassName("com.example", "Content")

            // Batch parameter
            val batchKeyType = ClassName("kotlin.uuid", "Uuid")
            val batchReturnType = ClassName("com.example", "Author")
            val batchParamTypeRef = mockk<KSTypeReference>()
            val batchParamType = mockk<KSType>()
            every { batchParamTypeRef.resolve() } returns batchParamType

            val keyTypeArg = mockk<KSTypeArgument>()
            val returnTypeArg = mockk<KSTypeArgument>()
            val keyTypeRef = mockk<KSTypeReference>()
            val returnTypeRef = mockk<KSTypeReference>()
            every { batchParamType.arguments } returns listOf(keyTypeArg, returnTypeArg)
            every { keyTypeArg.type } returns keyTypeRef
            every { returnTypeArg.type } returns returnTypeRef
            every { keyTypeRef.toTypeName(any()) } returns batchKeyType
            every { keyTypeRef.toTypeName() } returns batchKeyType
            every { returnTypeRef.toTypeName(any()) } returns batchReturnType
            every { returnTypeRef.toTypeName() } returns batchReturnType

            val batchTypeName = Types.Batch.parameterizedBy(batchKeyType, batchReturnType)
            every { batchParamType.toTypeName(any()) } returns batchTypeName
            every { batchParamType.toTypeName() } returns batchTypeName

            val batchParam = mockk<KSValueParameter>()
            every { batchParam.name } returns mockKSName("batch")
            every { batchParam.type } returns batchParamTypeRef

            val fn = mockFunction("getAuthors", parameters = listOf(batchParam))
            every { fn.modifiers } returns emptySet()
            every { fn.parentDeclaration } returns mockk<KSDeclaration> {
                every { qualifiedName } returns mockKSName("com.example.ContentController")
            }

            val type = Types.PropertyDataFetcher.parameterizedBy(
                ClassName("java.util.concurrent", "CompletableFuture").parameterizedBy(batchReturnType)
            )
            val prop = generator.newFieldProperty(fn, targetClass, type, batchParam)

            val code = prop.initializer.toString()
            assertTrue("Should use the request DataLoader registry", code.contains("dataLoaderRegistry.getOrPutLoader"))
            assertTrue("Should construct the native batch environment", code.contains("BatchLoaderEnvironment"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    // ── appendBatchFunction ──

    /**
     * Creates the mock infrastructure for [appendBatchFunction] tests.
     * Returns a tuple of (function, sourceParameterType, batchType, args).
     */
    private fun setupBatchMocks(
        batchKeyProperty: String = "id",
        batchKeyType: KSType? = null,
    ): BatchTestSetup {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")

        val keyTypeName = ClassName("kotlin.uuid", "Uuid")
        val returnTypeName = ClassName("com.example", "Result")
        val sourceTypeName = ClassName("com.example", "Content")

        // batch type with 2 type arguments: key and return
        val batchType = mockk<KSType>()
        val keyTypeArg = mockk<KSTypeArgument>()
        val returnTypeArg = mockk<KSTypeArgument>()
        val keyTypeRef = mockk<KSTypeReference>()
        val returnTypeRef = mockk<KSTypeReference>()

        every { batchType.arguments } returns listOf(keyTypeArg, returnTypeArg)
        every { keyTypeArg.type } returns keyTypeRef
        every { returnTypeArg.type } returns returnTypeRef
        every { keyTypeRef.toTypeName(any()) } returns keyTypeName
        every { keyTypeRef.toTypeName() } returns keyTypeName
        every { returnTypeRef.toTypeName(any()) } returns returnTypeName
        every { returnTypeRef.toTypeName() } returns returnTypeName

        // source parameter type
        val sourceParameterType = mockk<KSType>()
        every { sourceParameterType.toTypeName(any()) } returns sourceTypeName
        every { sourceParameterType.toTypeName() } returns sourceTypeName

        // BatchKey annotation on source declaration
        val batchKeyAnnotation = mockk<KSAnnotation>()
        every { batchKeyAnnotation.shortName } returns mockKSName("BatchKey")

        val propertyArg = mockk<KSValueArgument>()
        every { propertyArg.name } returns mockKSName("property")
        every { propertyArg.value } returns batchKeyProperty

        val typeArg = mockk<KSValueArgument>()
        every { typeArg.name } returns mockKSName("type")
        every { typeArg.value } returns (batchKeyType ?: mockk<KSType>())

        if (batchKeyType != null) {
            every { batchKeyType.toTypeName(any()) } returns keyTypeName
            every { batchKeyType.toTypeName() } returns keyTypeName
        }

        every { batchKeyAnnotation.arguments } returns listOf(propertyArg, typeArg)

        val sourceDecl = mockk<KSDeclaration>()
        every { sourceParameterType.declaration } returns sourceDecl
        every { sourceDecl.annotations } returns sequenceOf(batchKeyAnnotation)
        every { sourceDecl.qualifiedName } returns mockKSName("com.example.Content")

        // function with parent declaration and a batch parameter
        val batchParam = mockTypedParameter("batch", Types.Batch)
        val fn = mockFunction("loadItems", parameters = listOf(batchParam))
        val parentDecl = mockk<KSDeclaration>()
        every { fn.parentDeclaration } returns parentDecl
        every { parentDecl.qualifiedName } returns mockKSName("com.example.ContentController")

        return BatchTestSetup(fn, sourceParameterType, batchType)
    }

    private data class BatchTestSetup(
        val function: KSFunctionDeclaration,
        val sourceParameterType: KSType,
        val batchType: KSType,
    )

    @Test
    fun `appendBatchFunction with property-based batch key`() {
        val (fn, sourceType, batchType) = setupBatchMocks(batchKeyProperty = "id")
        try {
            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) {
                sb.appendBatchFunction(fn, "content", sourceType, args, batchType)
            }

            val code = sb.toString()
            assertTrue("Should set up call", code.contains("ServerCall"))
            assertTrue("Should get source", code.contains("sourceAs"))
            assertTrue("Should create dataLoader", code.contains("dataLoaderRegistry"))
            assertTrue("Should create BatchLoaderEnvironment", code.contains("BatchLoaderEnvironment"))
            assertTrue("Should create Batch", code.contains("Batch"))
            assertTrue("Should call controller", code.contains("controller."))
            assertTrue("Should get results", code.contains("batch.getResults()"))
            assertTrue("Should scope the loader cache by coerced arguments", code.contains("environment.arguments"))
            // Property-based key: uses %L?.%L pattern
            assertTrue("Should load with property key", code.contains("dataLoader.load"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `appendBatchFunction with type-based batch key`() {
        val typeKey = mockk<KSType>()
        val (fn, sourceType, batchType) = setupBatchMocks(batchKeyProperty = "", batchKeyType = typeKey)
        try {
            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) {
                sb.appendBatchFunction(fn, "content", sourceType, args, batchType)
            }

            val code = sb.toString()
            assertTrue("Should create dataLoader", code.contains("dataLoaderRegistry"))
            // Type-based key: uses %T(%L ?: error(...)) pattern
            assertTrue("Should load with dataLoader", code.contains("dataLoader.load"))
            assertTrue("Should wrap with BatchContext", code.contains("BatchContext"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `appendBatchFunction passes correct special parameters to controller`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val keyTypeName = ClassName("kotlin.uuid", "Uuid")
            val returnTypeName = ClassName("com.example", "Result")
            val sourceTypeName = ClassName("com.example", "Content")

            val batchType = mockk<KSType>()
            val keyTypeArg = mockk<KSTypeArgument>()
            val returnTypeArg = mockk<KSTypeArgument>()
            val keyTypeRef = mockk<KSTypeReference>()
            val returnTypeRef = mockk<KSTypeReference>()
            every { batchType.arguments } returns listOf(keyTypeArg, returnTypeArg)
            every { keyTypeArg.type } returns keyTypeRef
            every { returnTypeArg.type } returns returnTypeRef
            every { keyTypeRef.toTypeName(any()) } returns keyTypeName
            every { keyTypeRef.toTypeName() } returns keyTypeName
            every { returnTypeRef.toTypeName(any()) } returns returnTypeName
            every { returnTypeRef.toTypeName() } returns returnTypeName

            val sourceParameterType = mockk<KSType>()
            every { sourceParameterType.toTypeName(any()) } returns sourceTypeName
            every { sourceParameterType.toTypeName() } returns sourceTypeName

            // BatchKey annotation
            val batchKeyAnnotation = mockk<KSAnnotation>()
            every { batchKeyAnnotation.shortName } returns mockKSName("BatchKey")
            val propertyArg = mockk<KSValueArgument>()
            every { propertyArg.name } returns mockKSName("property")
            every { propertyArg.value } returns "id"
            every { batchKeyAnnotation.arguments } returns listOf(propertyArg)
            val sourceDecl = mockk<KSDeclaration>()
            every { sourceParameterType.declaration } returns sourceDecl
            every { sourceDecl.annotations } returns sequenceOf(batchKeyAnnotation)
            every { sourceDecl.qualifiedName } returns mockKSName("com.example.Content")

            // Function with multiple special parameter types
            val envParam = mockTypedParameter("env", Types.ResolverContext)
            val callParam = mockTypedParameter("call", Types.ServerCall)
            val authParam = mockTypedParameter("auth", Types.AuthenticationContext)
            val batchEnvParam = mockTypedParameter("batchEnv", Types.BatchLoaderEnvironment)
            val batchParam = mockTypedParameter("batch", Types.Batch)
            val fn = mockFunction("loadItems", parameters = listOf(envParam, callParam, authParam, batchEnvParam, batchParam))
            val parentDecl = mockk<KSDeclaration>()
            every { fn.parentDeclaration } returns parentDecl
            every { parentDecl.qualifiedName } returns mockKSName("com.example.Controller")

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) {
                sb.appendBatchFunction(fn, "content", sourceParameterType, args, batchType)
            }

            val code = sb.toString()
            // Each parameter type maps to its special name in the controller call
            assertTrue("Should pass environment", code.contains("environment, "))
            assertTrue("Should pass call", code.contains("call, "))
            assertTrue("Should pass authenticationContext", code.contains("authenticationContext, "))
            assertTrue("Should pass batchEnvironment", code.contains("batchEnvironment, "))
            assertTrue("Should pass batch", code.contains("batch, "))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `appendBatchFunction with ParameterizedTypeName param uses rawType`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val keyTypeName = ClassName("kotlin.uuid", "Uuid")
            val returnTypeName = ClassName("com.example", "Result")
            val sourceTypeName = ClassName("com.example", "Content")

            val batchType = mockk<KSType>()
            val keyTypeArg = mockk<KSTypeArgument>()
            val returnTypeArg = mockk<KSTypeArgument>()
            val keyTypeRef = mockk<KSTypeReference>()
            val returnTypeRef = mockk<KSTypeReference>()
            every { batchType.arguments } returns listOf(keyTypeArg, returnTypeArg)
            every { keyTypeArg.type } returns keyTypeRef
            every { returnTypeArg.type } returns returnTypeRef
            every { keyTypeRef.toTypeName(any()) } returns keyTypeName
            every { keyTypeRef.toTypeName() } returns keyTypeName
            every { returnTypeRef.toTypeName(any()) } returns returnTypeName
            every { returnTypeRef.toTypeName() } returns returnTypeName

            val sourceParameterType = mockk<KSType>()
            every { sourceParameterType.toTypeName(any()) } returns sourceTypeName
            every { sourceParameterType.toTypeName() } returns sourceTypeName

            val batchKeyAnnotation = mockk<KSAnnotation>()
            every { batchKeyAnnotation.shortName } returns mockKSName("BatchKey")
            val propertyArg = mockk<KSValueArgument>()
            every { propertyArg.name } returns mockKSName("property")
            every { propertyArg.value } returns "id"
            every { batchKeyAnnotation.arguments } returns listOf(propertyArg)
            val sourceDecl = mockk<KSDeclaration>()
            every { sourceParameterType.declaration } returns sourceDecl
            every { sourceDecl.annotations } returns sequenceOf(batchKeyAnnotation)
            every { sourceDecl.qualifiedName } returns mockKSName("com.example.Content")

            // Param with ParameterizedTypeName — exercises the `it.rawType` branch (line 336)
            val batchParamTypeName = Types.Batch.parameterizedBy(keyTypeName, returnTypeName)
            val parameterizedParam = mockTypedParameter("batch", batchParamTypeName)

            val fn = mockFunction("loadItems", parameters = listOf(parameterizedParam))
            val parentDecl = mockk<KSDeclaration>()
            every { fn.parentDeclaration } returns parentDecl
            every { parentDecl.qualifiedName } returns mockKSName("com.example.Controller")

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            with(generator) {
                sb.appendBatchFunction(fn, "content", sourceParameterType, args, batchType)
            }

            val code = sb.toString()
            // ParameterizedTypeName.rawType = Types.Batch → falls into else → "batch, "
            assertTrue("Should pass batch for parameterized type", code.contains("batch, "))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    // ── generate ──

    /**
     * Helper that sets up a minimal [FoundTypeController] with a single @Field suspend function
     * and invokes [generate]. Returns the generated code written to the [ByteArrayOutputStream].
     */
    private fun generateWithSuspendFunction(): String {
        val output = ByteArrayOutputStream()
        every { codeGenerator.createNewFile(any(), any(), any(), any()) } returns output

        val targetClass = mockk<KSType>()
        every { targetClass.toClassName() } returns ClassName("com.example", "Content")

        val controllerClassName = ClassName("com.example", "ContentController")
        val dispatcherClassName = ClassName("com.example.generated", "ContentDispatcher")

        val classDecl = mockk<KSClassDeclaration>()
        every { classDecl.toClassName() } returns controllerClassName
        every { classDecl.containingFile } returns mockk<KSFile>(relaxed = true)

        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val fn = mockFunction("getTitle", annotations = listOf(mockFieldAnnotation()))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns setOf(Modifier.SUSPEND)

        every { classDecl.getAllFunctions() } returns sequenceOf(fn)

        val found = FoundTypeController(
            targetClass = targetClass,
            typeName = "Content",
            controller = controllerClassName,
            controllerProvider = ClassName("com.example.generated", "ContentControllerProvider"),
            dispatcher = dispatcherClassName,
            dispatcherProvider = ClassName("com.example.generated", "ContentDispatcherProvider"),
            classDeclaration = classDecl,
        )

        generator.generate(listOf(found))
        return output.toString(Charsets.UTF_8)
    }

    @Test
    fun `generate produces dispatcher class with correct name`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val code = generateWithSuspendFunction()
            assertTrue("Should define dispatcher class", code.contains("class ContentDispatcher"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `generate includes controller constructor parameter`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val code = generateWithSuspendFunction()
            assertTrue("Should have controller constructor param", code.contains("controller"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `generate includes json and tracer lazy properties`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val code = generateWithSuspendFunction()
            assertTrue("Should have json property", code.contains("json"))
            assertTrue("Should have tracer property", code.contains("tracer"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `generate includes type wiring property`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val code = generateWithSuspendFunction()
            assertTrue("Should have type wiring", code.contains("newTypeWiring"))
            assertTrue("Should wire Content type", code.contains("Content"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `generate creates SuspendDataFetcher for suspend function`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val code = generateWithSuspendFunction()
            assertTrue("Should create SuspendDataFetcher", code.contains("SuspendDataFetcher"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `generate creates PropertyDataFetcher for non-suspend non-flow function`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val output = ByteArrayOutputStream()
            every { codeGenerator.createNewFile(any(), any(), any(), any()) } returns output

            val targetClass = mockk<KSType>()
            every { targetClass.toClassName() } returns ClassName("com.example", "Content")

            val classDecl = mockk<KSClassDeclaration>()
            every { classDecl.toClassName() } returns ClassName("com.example", "ContentController")
            every { classDecl.containingFile } returns mockk<KSFile>(relaxed = true)

            val returnTypeRef = mockk<KSTypeReference>()
            every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
            every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

            // Non-suspend, non-flow function
            val fn = mockFunction("getTitle", annotations = listOf(mockFieldAnnotation()))
            every { fn.returnType } returns returnTypeRef
            every { fn.modifiers } returns emptySet()

            every { classDecl.getAllFunctions() } returns sequenceOf(fn)

            val found = FoundTypeController(
                targetClass = targetClass,
                typeName = "Content",
                controller = ClassName("com.example", "ContentController"),
                controllerProvider = ClassName("com.example.generated", "ContentControllerProvider"),
                dispatcher = ClassName("com.example.generated", "ContentDispatcher"),
                dispatcherProvider = ClassName("com.example.generated", "ContentDispatcherProvider"),
                classDeclaration = classDecl,
            )

            generator.generate(listOf(found))
            val code = output.toString(Charsets.UTF_8)
            assertTrue("Should create PropertyDataFetcher", code.contains("PropertyDataFetcher"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `generate creates FlowDataFetcher for Flow return type`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val output = ByteArrayOutputStream()
            every { codeGenerator.createNewFile(any(), any(), any(), any()) } returns output

            val targetClass = mockk<KSType>()
            every { targetClass.toClassName() } returns ClassName("com.example", "Content")

            val classDecl = mockk<KSClassDeclaration>()
            every { classDecl.toClassName() } returns ClassName("com.example", "ContentController")
            every { classDecl.containingFile } returns mockk<KSFile>(relaxed = true)

            val flowType = ClassName("kotlinx.coroutines.flow", "Flow").parameterizedBy(ClassName("kotlin", "String"))
            val returnTypeRef = mockk<KSTypeReference>()
            every { returnTypeRef.toTypeName(any()) } returns flowType
            every { returnTypeRef.toTypeName() } returns flowType

            // Non-suspend function returning Flow
            val fn = mockFunction("streamTitle", annotations = listOf(mockFieldAnnotation()))
            every { fn.returnType } returns returnTypeRef
            every { fn.modifiers } returns emptySet()

            every { classDecl.getAllFunctions() } returns sequenceOf(fn)

            val found = FoundTypeController(
                targetClass = targetClass,
                typeName = "Content",
                controller = ClassName("com.example", "ContentController"),
                controllerProvider = ClassName("com.example.generated", "ContentControllerProvider"),
                dispatcher = ClassName("com.example.generated", "ContentDispatcher"),
                dispatcherProvider = ClassName("com.example.generated", "ContentDispatcherProvider"),
                classDeclaration = classDecl,
            )

            generator.generate(listOf(found))
            val code = output.toString(Charsets.UTF_8)
            assertTrue("Should create FlowDataFetcher", code.contains("FlowDataFetcher"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `generate creates batch PropertyDataFetcher for Batch parameter function`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val output = ByteArrayOutputStream()
            every { codeGenerator.createNewFile(any(), any(), any(), any()) } returns output

            val targetClass = mockk<KSType>()
            val targetClassName = ClassName("com.example", "Content")
            every { targetClass.toClassName() } returns targetClassName

            // BatchKey annotation on targetClass declaration
            val batchKeyAnnotation = mockk<KSAnnotation>()
            every { batchKeyAnnotation.shortName } returns mockKSName("BatchKey")
            val propertyArg = mockk<KSValueArgument>()
            every { propertyArg.name } returns mockKSName("property")
            every { propertyArg.value } returns "id"
            every { batchKeyAnnotation.arguments } returns listOf(propertyArg)
            val targetDecl = mockk<KSDeclaration>()
            every { targetClass.declaration } returns targetDecl
            every { targetDecl.annotations } returns sequenceOf(batchKeyAnnotation)
            every { targetDecl.qualifiedName } returns mockKSName("com.example.Content")
            every { targetClass.toTypeName(any()) } returns targetClassName
            every { targetClass.toTypeName() } returns targetClassName

            val classDecl = mockk<KSClassDeclaration>()
            val controllerClassName = ClassName("com.example", "ContentController")
            every { classDecl.toClassName() } returns controllerClassName
            every { classDecl.containingFile } returns mockk<KSFile>(relaxed = true)

            // Return type for the batch function
            val returnTypeRef = mockk<KSTypeReference>()
            val returnType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("com.example", "Author"))
            every { returnTypeRef.toTypeName(any()) } returns returnType
            every { returnTypeRef.toTypeName() } returns returnType

            // Batch parameter: Batch<UUID, List<Author>>
            val batchKeyType = ClassName("kotlin.uuid", "Uuid")
            val batchReturnType = ClassName("com.example", "Author")

            val batchParamTypeRef = mockk<KSTypeReference>()
            val batchParamType = mockk<KSType>()
            val batchDecl = mockk<KSClassDeclaration>()
            every { batchParamTypeRef.resolve() } returns batchParamType
            every { batchParamType.declaration } returns batchDecl
            every { batchDecl.qualifiedName } returns mockKSName(Types.Batch.canonicalName)

            // Batch type arguments: [UUID, List<Author>]
            val keyTypeArg = mockk<KSTypeArgument>()
            val returnTypeArg = mockk<KSTypeArgument>()
            val keyTypeArgRef = mockk<KSTypeReference>()
            val returnTypeArgRef = mockk<KSTypeReference>()
            val resolvedReturnType = mockk<KSType>()
            every { batchParamType.arguments } returns listOf(keyTypeArg, returnTypeArg)
            every { keyTypeArg.type } returns keyTypeArgRef
            every { returnTypeArg.type } returns returnTypeArgRef
            every { keyTypeArgRef.toTypeName(any()) } returns batchKeyType
            every { keyTypeArgRef.toTypeName() } returns batchKeyType
            every { returnTypeArgRef.toTypeName(any()) } returns batchReturnType
            every { returnTypeArgRef.toTypeName() } returns batchReturnType
            every { returnTypeArgRef.resolve() } returns resolvedReturnType
            every { resolvedReturnType.toTypeName(any()) } returns batchReturnType
            every { resolvedReturnType.toTypeName() } returns batchReturnType
            // appendBatchFunction iterates parameters and calls toTypeName() on each resolved type
            val batchTypeName = Types.Batch.parameterizedBy(batchKeyType, batchReturnType)
            every { batchParamType.toTypeName(any()) } returns batchTypeName
            every { batchParamType.toTypeName() } returns batchTypeName

            val batchParam = mockk<KSValueParameter>()
            every { batchParam.name } returns mockKSName("batch")
            every { batchParam.type } returns batchParamTypeRef

            val fn = mockFunction("getAuthors", annotations = listOf(mockFieldAnnotation()), parameters = listOf(batchParam))
            every { fn.returnType } returns returnTypeRef
            every { fn.modifiers } returns setOf(Modifier.SUSPEND)
            every { fn.parentDeclaration } returns mockk<KSDeclaration> {
                every { qualifiedName } returns mockKSName("com.example.ContentController")
            }

            every { classDecl.getAllFunctions() } returns sequenceOf(fn)

            val found = FoundTypeController(
                targetClass = targetClass,
                typeName = "Content",
                controller = controllerClassName,
                controllerProvider = ClassName("com.example.generated", "ContentControllerProvider"),
                dispatcher = ClassName("com.example.generated", "ContentDispatcher"),
                dispatcherProvider = ClassName("com.example.generated", "ContentDispatcherProvider"),
                classDeclaration = classDecl,
            )

            generator.generate(listOf(found))
            val code = output.toString(Charsets.UTF_8)
            assertTrue("Should create PropertyDataFetcher for batch", code.contains("PropertyDataFetcher"))
            assertTrue("Should contain native batch loader environment", code.contains("BatchLoaderEnvironment"))
            assertTrue("Should contain request DataLoader registry", code.contains("dataLoaderRegistry.getOrPutLoader"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `generate adds Generated annotation`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val code = generateWithSuspendFunction()
            assertTrue("Should have @Generated annotation", code.contains("@Generated"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `generate adds required imports`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val code = generateWithSuspendFunction()
            assertTrue("Should import anyToJsonElement", code.contains("anyToJsonElement"))
            assertTrue("Should import runBlocking", code.contains("runBlocking"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `generate implements Dispatcher interface`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val code = generateWithSuspendFunction()
            assertTrue("Should implement Dispatcher", code.contains("Dispatcher"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `generate skips functions without Field annotation`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val output = ByteArrayOutputStream()
            every { codeGenerator.createNewFile(any(), any(), any(), any()) } returns output

            val targetClass = mockk<KSType>()
            every { targetClass.toClassName() } returns ClassName("com.example", "Content")

            val classDecl = mockk<KSClassDeclaration>()
            every { classDecl.toClassName() } returns ClassName("com.example", "ContentController")
            every { classDecl.containingFile } returns mockk<KSFile>(relaxed = true)

            // Field function
            val returnTypeRef = mockk<KSTypeReference>()
            every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
            every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

            val fieldFn = mockFunction("getTitle", annotations = listOf(mockFieldAnnotation()))
            every { fieldFn.returnType } returns returnTypeRef
            every { fieldFn.modifiers } returns setOf(Modifier.SUSPEND)

            // Non-field function (no @Field annotation)
            val helperFn = mockFunction("helperMethod", annotations = emptyList())

            every { classDecl.getAllFunctions() } returns sequenceOf(fieldFn, helperFn)

            val found = FoundTypeController(
                targetClass = targetClass,
                typeName = "Content",
                controller = ClassName("com.example", "ContentController"),
                controllerProvider = ClassName("com.example.generated", "ContentControllerProvider"),
                dispatcher = ClassName("com.example.generated", "ContentDispatcher"),
                dispatcherProvider = ClassName("com.example.generated", "ContentDispatcherProvider"),
                classDeclaration = classDecl,
            )

            generator.generate(listOf(found))
            val code = output.toString(Charsets.UTF_8)
            assertTrue("Should have getTitleDataFetcher", code.contains("getTitleDataFetcher"))
            assertTrue("Should NOT have helperMethodDataFetcher", !code.contains("helperMethodDataFetcher"))
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `generate throws when containingFile is null`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val targetClass = mockk<KSType>()
            every { targetClass.toClassName() } returns ClassName("com.example", "Content")

            val classDecl = mockk<KSClassDeclaration>()
            every { classDecl.toClassName() } returns ClassName("com.example", "ContentController")
            every { classDecl.containingFile } returns null
            every { classDecl.getAllFunctions() } returns emptySequence()

            val found = FoundTypeController(
                targetClass = targetClass,
                typeName = "Content",
                controller = ClassName("com.example", "ContentController"),
                controllerProvider = ClassName("com.example.generated", "ContentControllerProvider"),
                dispatcher = ClassName("com.example.generated", "ContentDispatcher"),
                dispatcherProvider = ClassName("com.example.generated", "ContentDispatcherProvider"),
                classDeclaration = classDecl,
            )

            try {
                generator.generate(listOf(found))
                fail("Should throw on null containingFile")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("No containing file"))
            }
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `generate with empty items does nothing`() {
        // Empty collection should not throw or write any files
        generator.generate(emptyList())
    }

    // ── Error / defensive branch tests ──

    @Test
    fun `generate throws when returnType is null`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val output = ByteArrayOutputStream()
            every { codeGenerator.createNewFile(any(), any(), any(), any()) } returns output

            val targetClass = mockk<KSType>()
            every { targetClass.toClassName() } returns ClassName("com.example", "Content")

            val classDecl = mockk<KSClassDeclaration>()
            every { classDecl.toClassName() } returns ClassName("com.example", "ContentController")
            every { classDecl.containingFile } returns mockk<KSFile>(relaxed = true)

            val fn = mockFunction("getTitle", annotations = listOf(mockFieldAnnotation()))
            every { fn.returnType } returns null
            every { fn.modifiers } returns emptySet()

            every { classDecl.getAllFunctions() } returns sequenceOf(fn)

            val found = FoundTypeController(
                targetClass = targetClass,
                typeName = "Content",
                controller = ClassName("com.example", "ContentController"),
                controllerProvider = ClassName("com.example.generated", "ContentControllerProvider"),
                dispatcher = ClassName("com.example.generated", "ContentDispatcher"),
                dispatcherProvider = ClassName("com.example.generated", "ContentDispatcherProvider"),
                classDeclaration = classDecl,
            )

            try {
                generator.generate(listOf(found))
                fail("Should throw on null returnType")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("No return type found"))
            }
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `generate throws when batch type argument type is null`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val output = ByteArrayOutputStream()
            every { codeGenerator.createNewFile(any(), any(), any(), any()) } returns output

            val targetClass = mockk<KSType>()
            every { targetClass.toClassName() } returns ClassName("com.example", "Content")

            val classDecl = mockk<KSClassDeclaration>()
            every { classDecl.toClassName() } returns ClassName("com.example", "ContentController")
            every { classDecl.containingFile } returns mockk<KSFile>(relaxed = true)

            val returnTypeRef = mockk<KSTypeReference>()
            every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
            every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

            // Batch parameter with null last type argument
            val batchParamTypeRef = mockk<KSTypeReference>()
            val batchParamType = mockk<KSType>()
            val batchDecl = mockk<KSClassDeclaration>()
            every { batchParamTypeRef.resolve() } returns batchParamType
            every { batchParamType.declaration } returns batchDecl
            every { batchDecl.qualifiedName } returns mockKSName(Types.Batch.canonicalName)

            val keyTypeArg = mockk<KSTypeArgument>()
            val returnTypeArg = mockk<KSTypeArgument>()
            every { batchParamType.arguments } returns listOf(keyTypeArg, returnTypeArg)
            every { keyTypeArg.type } returns mockk<KSTypeReference>()
            // Last type argument has null type
            every { returnTypeArg.type } returns null

            val batchParam = mockk<KSValueParameter>()
            every { batchParam.name } returns mockKSName("batch")
            every { batchParam.type } returns batchParamTypeRef

            val fn = mockFunction("getItems", annotations = listOf(mockFieldAnnotation()), parameters = listOf(batchParam))
            every { fn.returnType } returns returnTypeRef
            every { fn.modifiers } returns emptySet()

            every { classDecl.getAllFunctions() } returns sequenceOf(fn)

            val found = FoundTypeController(
                targetClass = targetClass,
                typeName = "Content",
                controller = ClassName("com.example", "ContentController"),
                controllerProvider = ClassName("com.example.generated", "ContentControllerProvider"),
                dispatcher = ClassName("com.example.generated", "ContentDispatcher"),
                dispatcherProvider = ClassName("com.example.generated", "ContentDispatcherProvider"),
                classDeclaration = classDecl,
            )

            try {
                generator.generate(listOf(found))
                fail("Should throw on null batch type argument")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("missing type"))
            }
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `newFieldProperty throws when batch parameter name is null`() {
        val targetClass = setupFieldPropertyMocks()
        try {
            val batchParam = mockk<KSValueParameter>()
            every { batchParam.name } returns null

            val fn = mockFunction("getItems")
            every { fn.modifiers } returns emptySet()

            val type = Types.SuspendDataFetcher.parameterizedBy(ClassName("kotlin", "String"))
            try {
                generator.newFieldProperty(fn, targetClass, type, batchParam)
                fail("Should throw on null batch parameter name")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("Parameter name not found"))
            }
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `newFieldProperty throws when regular parameter name is null`() {
        val targetClass = setupFieldPropertyMocks()
        try {
            val param = mockk<KSValueParameter>()
            every { param.name } returns null
            val typeRef = mockk<KSTypeReference>()
            val ksType = mockk<KSType>()
            every { param.type } returns typeRef
            every { typeRef.resolve() } returns ksType
            every { ksType.toTypeName(any()) } returns ClassName("kotlin", "String")
            every { ksType.toTypeName() } returns ClassName("kotlin", "String")

            val fn = mockFunction("getItem", parameters = listOf(param))
            every { fn.modifiers } returns emptySet()

            val type = Types.SuspendDataFetcher.parameterizedBy(ClassName("kotlin", "String"))
            try {
                generator.newFieldProperty(fn, targetClass, type, null)
                fail("Should throw on null parameter name")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("Parameter name not found"))
            }
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `newFieldProperty throws TODO for unsupported TypeName`() {
        val targetClass = setupFieldPropertyMocks()
        try {
            val typeVarName = TypeVariableName("T")
            val param = mockk<KSValueParameter>()
            val typeRef = mockk<KSTypeReference>()
            val ksType = mockk<KSType>()
            every { param.name } returns mockKSName("item")
            every { param.type } returns typeRef
            every { typeRef.resolve() } returns ksType
            every { ksType.toTypeName(any()) } returns typeVarName
            every { ksType.toTypeName() } returns typeVarName

            val fn = mockFunction("getItem", parameters = listOf(param))
            every { fn.modifiers } returns emptySet()

            val type = Types.SuspendDataFetcher.parameterizedBy(ClassName("kotlin", "String"))
            try {
                generator.newFieldProperty(fn, targetClass, type, null)
                fail("Should throw TODO for unsupported TypeName")
            } catch (e: NotImplementedError) {
                assertTrue(e.message!!.contains("unsupported field property"))
            }
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `appendControllerFunction throws when parameter name is null`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        try {
            val param = mockk<KSValueParameter>()
            every { param.name } returns null
            val typeRef = mockk<KSTypeReference>()
            val ksType = mockk<KSType>()
            every { param.type } returns typeRef
            every { typeRef.resolve() } returns ksType
            every { ksType.toTypeName(any()) } returns ClassName("kotlin", "String")
            every { ksType.toTypeName() } returns ClassName("kotlin", "String")

            val fn = mockFunction("doAction", parameters = listOf(param))

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            try {
                with(generator) { sb.appendControllerFunction(fn, args) }
                fail("Should throw on null parameter name")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("Parameter name not found"))
            }
        } finally {
            unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        }
    }

    @Test
    fun `appendBatchFunction throws when key type argument is null`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val sourceParameterType = mockk<KSType>()
            every { sourceParameterType.toTypeName(any()) } returns ClassName("com.example", "Content")
            every { sourceParameterType.toTypeName() } returns ClassName("com.example", "Content")

            val batchType = mockk<KSType>()
            val keyTypeArg = mockk<KSTypeArgument>()
            every { keyTypeArg.type } returns null
            val returnTypeArg = mockk<KSTypeArgument>()
            every { batchType.arguments } returns listOf(keyTypeArg, returnTypeArg)

            val fn = mockFunction("loadItems", parameters = emptyList())
            every { fn.parentDeclaration } returns mockk<KSDeclaration> {
                every { qualifiedName } returns mockKSName("com.example.Controller")
            }

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            try {
                with(generator) { sb.appendBatchFunction(fn, "content", sourceParameterType, args, batchType) }
                fail("Should throw on null key type argument")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("missing type"))
            }
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `appendBatchFunction throws when return type argument is null`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val sourceParameterType = mockk<KSType>()
            every { sourceParameterType.toTypeName(any()) } returns ClassName("com.example", "Content")
            every { sourceParameterType.toTypeName() } returns ClassName("com.example", "Content")

            val batchType = mockk<KSType>()
            val keyTypeArg = mockk<KSTypeArgument>()
            val keyTypeRef = mockk<KSTypeReference>()
            every { keyTypeArg.type } returns keyTypeRef
            every { keyTypeRef.toTypeName(any()) } returns ClassName("kotlin.uuid", "Uuid")
            every { keyTypeRef.toTypeName() } returns ClassName("kotlin.uuid", "Uuid")

            val returnTypeArg = mockk<KSTypeArgument>()
            every { returnTypeArg.type } returns null
            every { batchType.arguments } returns listOf(keyTypeArg, returnTypeArg)

            val fn = mockFunction("loadItems", parameters = emptyList())
            every { fn.parentDeclaration } returns mockk<KSDeclaration> {
                every { qualifiedName } returns mockKSName("com.example.Controller")
            }

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            try {
                with(generator) { sb.appendBatchFunction(fn, "content", sourceParameterType, args, batchType) }
                fail("Should throw on null return type argument")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("missing type"))
            }
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    @Test
    fun `appendBatchFunction throws when BatchKey annotation is missing`() {
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
        try {
            val keyTypeName = ClassName("kotlin.uuid", "Uuid")
            val returnTypeName = ClassName("com.example", "Result")
            val sourceTypeName = ClassName("com.example", "Content")

            val batchType = mockk<KSType>()
            val keyTypeArg = mockk<KSTypeArgument>()
            val returnTypeArg = mockk<KSTypeArgument>()
            val keyTypeRef = mockk<KSTypeReference>()
            val returnTypeRef = mockk<KSTypeReference>()
            every { batchType.arguments } returns listOf(keyTypeArg, returnTypeArg)
            every { keyTypeArg.type } returns keyTypeRef
            every { returnTypeArg.type } returns returnTypeRef
            every { keyTypeRef.toTypeName(any()) } returns keyTypeName
            every { keyTypeRef.toTypeName() } returns keyTypeName
            every { returnTypeRef.toTypeName(any()) } returns returnTypeName
            every { returnTypeRef.toTypeName() } returns returnTypeName

            val sourceParameterType = mockk<KSType>()
            every { sourceParameterType.toTypeName(any()) } returns sourceTypeName
            every { sourceParameterType.toTypeName() } returns sourceTypeName

            // Source declaration with NO BatchKey annotation
            val sourceDecl = mockk<KSDeclaration>()
            every { sourceParameterType.declaration } returns sourceDecl
            every { sourceDecl.annotations } returns emptySequence()
            every { sourceDecl.qualifiedName } returns mockKSName("com.example.Content")

            val batchParam = mockTypedParameter("batch", Types.Batch)
            val fn = mockFunction("loadItems", parameters = listOf(batchParam))
            every { fn.parentDeclaration } returns mockk<KSDeclaration> {
                every { qualifiedName } returns mockKSName("com.example.Controller")
            }

            val sb = StringBuilder()
            val args = mutableListOf<Any>()
            try {
                with(generator) { sb.appendBatchFunction(fn, "content", sourceParameterType, args, batchType) }
                fail("Should throw on missing BatchKey annotation")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("missing BatchType"))
            }
        } finally {
            cleanupFieldPropertyMocks()
        }
    }

    // ── member appendArgument delegation ──

    @Test
    fun `member appendArgument delegates to companion`() {
        val type = ClassName("kotlin", "String")
        val sbMember = StringBuilder()
        val argsMember = mutableListOf<Any>()
        val sbCompanion = StringBuilder()
        val argsCompanion = mutableListOf<Any>()

        with(generator) {
            sbMember.appendArgument(type, "x", argsMember)
        }
        ControllerDispatcherGenerator.appendArgument(sbCompanion, type, "x", argsCompanion)

        assertEquals(sbCompanion.toString(), sbMember.toString())
        assertEquals(argsCompanion, argsMember)
    }
}
