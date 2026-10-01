package bosca.ksp.generator.graphql

import bosca.ksp.Types
import bosca.ksp.visitors.FoundTypeController
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
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
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Golden file tests for [ControllerDispatcherGenerator] that verify the COMPLETE generated output
 * for representative scenarios. Each test generates code and compares it against an
 * expected golden file stored in test resources.
 *
 * To update golden files after intentional generator changes, set the environment variable
 * `UPDATE_GOLDEN=true` and run the tests.
 */
class ControllerDispatcherGeneratorGoldenTest {

    private val outputStream = ByteArrayOutputStream()
    private val codeGenerator = mockk<CodeGenerator>(relaxed = true) {
        every { createNewFile(any(), any(), any(), any()) } returns outputStream
    }
    private val logger = mockk<KSPLogger>(relaxed = true)
    private val generator = ControllerDispatcherGenerator(codeGenerator, logger)

    @Before
    fun setup() {
        outputStream.reset()
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        mockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
    }

    @After
    fun tearDown() {
        unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
        unmockkStatic("com.squareup.kotlinpoet.ksp.KsClassDeclarationsKt")
    }

    // ── Mock Helpers ──

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

    private fun mockFunction(
        name: String,
        annotations: List<KSAnnotation> = emptyList(),
        parameters: List<KSValueParameter> = emptyList()
    ): KSFunctionDeclaration {
        val function = mockk<KSFunctionDeclaration>()
        every { function.simpleName } returns mockKSName(name)
        every { function.annotations } returns annotations.asSequence()
        every { function.parameters } returns parameters
        return function
    }

    private fun mockTypedParameter(
        paramName: String,
        typeName: com.squareup.kotlinpoet.TypeName,
        nullable: Boolean = false,
        qualifiedName: String? = null
    ): KSValueParameter {
        val param = mockk<KSValueParameter>()
        val typeRef = mockk<KSTypeReference>()
        val ksType = mockk<KSType>()

        every { param.name } returns mockKSName(paramName)
        every { param.type } returns typeRef
        every { typeRef.resolve() } returns ksType
        every { ksType.toTypeName(any()) } returns typeName
        every { ksType.toTypeName() } returns typeName
        every { ksType.isMarkedNullable } returns nullable

        // Set up declaration for getBatchType() checks
        val decl = if (qualifiedName != null) {
            mockk<KSClassDeclaration>().also {
                every { it.qualifiedName } returns mockKSName(qualifiedName)
            }
        } else if (typeName is ClassName) {
            mockk<KSClassDeclaration>().also {
                every { it.qualifiedName } returns mockKSName(typeName.canonicalName)
            }
        } else {
            mockk<KSDeclaration>().also {
                every { it.qualifiedName } returns mockKSName("unknown")
            }
        }
        every { ksType.declaration } returns decl

        return param
    }

    private fun mockFoundTypeController(
        targetClassName: ClassName = ClassName("com.example", "Content"),
        controllerClassName: ClassName = ClassName("com.example", "ContentController"),
        typeName: String = "Content",
        functions: Sequence<KSFunctionDeclaration> = emptySequence()
    ): FoundTypeController {
        val targetClass = mockk<KSType>()
        every { targetClass.toClassName() } returns targetClassName

        val classDecl = mockk<KSClassDeclaration>()
        every { classDecl.toClassName() } returns controllerClassName
        every { classDecl.containingFile } returns mockk<KSFile>(relaxed = true)
        every { classDecl.getAllFunctions() } returns functions

        val dispatcherClassName = ClassName("com.example.generated", "${typeName}Dispatcher")

        return FoundTypeController(
            targetClass = targetClass,
            typeName = typeName,
            controller = controllerClassName,
            controllerProvider = ClassName("com.example.generated", "${typeName}ControllerProvider"),
            dispatcher = dispatcherClassName,
            dispatcherProvider = ClassName("com.example.generated", "${typeName}DispatcherProvider"),
            classDeclaration = classDecl,
        )
    }

    private fun generatedCode(): String = outputStream.toString(Charsets.UTF_8.name())

    // ── Golden File Assertion ──

    /**
     * Asserts that the generated code matches the golden file stored at
     * `src/test/resources/golden/controller/{goldenName}.txt`.
     *
     * If the golden file does not exist, it is created from the current output
     * and the test fails with instructions to review and re-run.
     *
     * Set `UPDATE_GOLDEN=true` environment variable to overwrite existing golden files.
     */
    private fun assertMatchesGolden(goldenName: String) {
        val code = generatedCode()
        val resourcePath = "golden/controller/$goldenName.txt"
        val resourceDir = File("src/test/resources/golden/controller")
        val goldenFile = File(resourceDir, "$goldenName.txt")

        val updateGolden = System.getenv("UPDATE_GOLDEN") == "true"

        if (!goldenFile.exists() || updateGolden) {
            resourceDir.mkdirs()
            goldenFile.writeText(code)
            if (!updateGolden) {
                fail(
                    "Golden file created at $resourcePath. " +
                        "Review the generated content and re-run the test to verify."
                )
            }
        }

        val expected = goldenFile.readText()
        assertEquals(
            expected,
            code,
            "Generated code does not match golden file: $resourcePath\n" +
                "If the change is intentional, run with UPDATE_GOLDEN=true to update golden files."
        )
    }

    // ── Golden File Tests ──

    @Test
    fun `golden - suspend function returning String`() {
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val fn = mockFunction("getTitle", annotations = listOf(mockFieldAnnotation()))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns setOf(Modifier.SUSPEND)

        val found = mockFoundTypeController(functions = sequenceOf(fn))
        generator.generate(listOf(found))
        assertMatchesGolden("suspend-function-string")
    }

    @Test
    fun `golden - non-suspend function returning String (PropertyDataFetcher)`() {
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val fn = mockFunction("getTitle", annotations = listOf(mockFieldAnnotation()))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns emptySet()

        val found = mockFoundTypeController(functions = sequenceOf(fn))
        generator.generate(listOf(found))
        assertMatchesGolden("property-function-string")
    }

    @Test
    fun `golden - Flow return type (FlowDataFetcher)`() {
        val flowType = ClassName("kotlinx.coroutines.flow", "Flow").parameterizedBy(ClassName("kotlin", "String"))
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns flowType
        every { returnTypeRef.toTypeName() } returns flowType

        val fn = mockFunction("streamTitle", annotations = listOf(mockFieldAnnotation()))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns emptySet()

        val found = mockFoundTypeController(functions = sequenceOf(fn))
        generator.generate(listOf(found))
        assertMatchesGolden("flow-function-string")
    }

    @Test
    fun `golden - suspend function with source parameter`() {
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val sourceParam = mockTypedParameter("content", ClassName("com.example", "Content"))

        val fn = mockFunction("getTitle", annotations = listOf(mockFieldAnnotation()), parameters = listOf(sourceParam))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns setOf(Modifier.SUSPEND)

        val found = mockFoundTypeController(functions = sequenceOf(fn))
        generator.generate(listOf(found))
        assertMatchesGolden("suspend-with-source")
    }

    @Test
    fun `golden - suspend function with primitive argument`() {
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val idParam = mockTypedParameter("id", ClassName("kotlin", "String"), nullable = false)

        val fn = mockFunction("getItem", annotations = listOf(mockFieldAnnotation()), parameters = listOf(idParam))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns setOf(Modifier.SUSPEND)

        val found = mockFoundTypeController(functions = sequenceOf(fn))
        generator.generate(listOf(found))
        assertMatchesGolden("suspend-with-primitive-arg")
    }

    @Test
    fun `golden - suspend function with nullable primitive argument`() {
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val nameParam = mockTypedParameter("name", ClassName("kotlin", "String").copy(nullable = true), nullable = true)

        val fn = mockFunction("search", annotations = listOf(mockFieldAnnotation()), parameters = listOf(nameParam))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns setOf(Modifier.SUSPEND)

        val found = mockFoundTypeController(functions = sequenceOf(fn))
        generator.generate(listOf(found))
        assertMatchesGolden("suspend-with-nullable-primitive-arg")
    }

    @Test
    fun `golden - suspend function with complex type argument`() {
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val inputParam = mockTypedParameter("input", ClassName("com.example", "MyInput"), nullable = false)

        val fn = mockFunction("createItem", annotations = listOf(mockFieldAnnotation()), parameters = listOf(inputParam))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns setOf(Modifier.SUSPEND)

        val found = mockFoundTypeController(functions = sequenceOf(fn))
        generator.generate(listOf(found))
        assertMatchesGolden("suspend-with-complex-arg")
    }

    @Test
    fun `golden - suspend function with nullable complex type argument`() {
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val inputParam = mockTypedParameter("input", ClassName("com.example", "MyInput").copy(nullable = true), nullable = true)

        val fn = mockFunction("updateItem", annotations = listOf(mockFieldAnnotation()), parameters = listOf(inputParam))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns setOf(Modifier.SUSPEND)

        val found = mockFoundTypeController(functions = sequenceOf(fn))
        generator.generate(listOf(found))
        assertMatchesGolden("suspend-with-nullable-complex-arg")
    }

    @Test
    fun `golden - suspend function with ResolverContext parameter`() {
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val envParam = mockTypedParameter("environment", Types.ResolverContext)

        val fn = mockFunction("getTitle", annotations = listOf(mockFieldAnnotation()), parameters = listOf(envParam))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns setOf(Modifier.SUSPEND)

        val found = mockFoundTypeController(functions = sequenceOf(fn))
        generator.generate(listOf(found))
        assertMatchesGolden("suspend-with-environment")
    }

    @Test
    fun `golden - suspend function with AuthenticationContext parameter`() {
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val authParam = mockTypedParameter("authenticationContext", Types.AuthenticationContext)

        val fn = mockFunction("getSecret", annotations = listOf(mockFieldAnnotation()), parameters = listOf(authParam))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns setOf(Modifier.SUSPEND)

        val found = mockFoundTypeController(functions = sequenceOf(fn))
        generator.generate(listOf(found))
        assertMatchesGolden("suspend-with-auth-context")
    }

    @Test
    fun `golden - suspend function with ApplicationCall parameter`() {
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val callParam = mockTypedParameter("call", Types.ServerCall)

        val fn = mockFunction("doAction", annotations = listOf(mockFieldAnnotation()), parameters = listOf(callParam))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns setOf(Modifier.SUSPEND)

        val found = mockFoundTypeController(functions = sequenceOf(fn))
        generator.generate(listOf(found))
        assertMatchesGolden("suspend-with-application-call")
    }

    @Test
    fun `golden - suspend function with mixed parameters`() {
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val sourceParam = mockTypedParameter("content", ClassName("com.example", "Content"))
        val envParam = mockTypedParameter("environment", Types.ResolverContext)
        val authParam = mockTypedParameter("authenticationContext", Types.AuthenticationContext)
        val idParam = mockTypedParameter("id", ClassName("kotlin", "Int"), nullable = false)

        val fn = mockFunction("getField", annotations = listOf(mockFieldAnnotation()), parameters = listOf(sourceParam, envParam, authParam, idParam))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns setOf(Modifier.SUSPEND)

        val found = mockFoundTypeController(functions = sequenceOf(fn))
        generator.generate(listOf(found))
        assertMatchesGolden("suspend-with-mixed-params")
    }

    @Test
    fun `golden - suspend function with List argument`() {
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
        val listParam = mockTypedParameter("names", listType, nullable = false)

        val fn = mockFunction("setNames", annotations = listOf(mockFieldAnnotation()), parameters = listOf(listParam))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns setOf(Modifier.SUSPEND)

        val found = mockFoundTypeController(functions = sequenceOf(fn))
        generator.generate(listOf(found))
        assertMatchesGolden("suspend-with-list-arg")
    }

    @Test
    fun `golden - suspend function with JsonElement argument`() {
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val jsonElementParam = mockTypedParameter("data", ClassName("kotlinx.serialization.json", "JsonElement"), nullable = false)

        val fn = mockFunction("processJson", annotations = listOf(mockFieldAnnotation()), parameters = listOf(jsonElementParam))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns setOf(Modifier.SUSPEND)

        val found = mockFoundTypeController(functions = sequenceOf(fn))
        generator.generate(listOf(found))
        assertMatchesGolden("suspend-with-json-element-arg")
    }

    @Test
    fun `golden - custom field name from annotation`() {
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val fn = mockFunction("getTitle", annotations = listOf(mockFieldAnnotation("customTitle")))
        every { fn.returnType } returns returnTypeRef
        every { fn.modifiers } returns setOf(Modifier.SUSPEND)

        val found = mockFoundTypeController(functions = sequenceOf(fn))
        generator.generate(listOf(found))
        assertMatchesGolden("custom-field-name")
    }

    @Test
    fun `golden - multiple Field functions`() {
        val stringReturnTypeRef = mockk<KSTypeReference>()
        every { stringReturnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { stringReturnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val intReturnTypeRef = mockk<KSTypeReference>()
        every { intReturnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "Int")
        every { intReturnTypeRef.toTypeName() } returns ClassName("kotlin", "Int")

        val fn1 = mockFunction("getTitle", annotations = listOf(mockFieldAnnotation()))
        every { fn1.returnType } returns stringReturnTypeRef
        every { fn1.modifiers } returns setOf(Modifier.SUSPEND)

        val fn2 = mockFunction("getDescription", annotations = listOf(mockFieldAnnotation()))
        every { fn2.returnType } returns stringReturnTypeRef
        every { fn2.modifiers } returns setOf(Modifier.SUSPEND)

        val fn3 = mockFunction("getCount", annotations = listOf(mockFieldAnnotation()))
        every { fn3.returnType } returns intReturnTypeRef
        every { fn3.modifiers } returns emptySet()

        val found = mockFoundTypeController(functions = sequenceOf(fn1, fn2, fn3))
        generator.generate(listOf(found))
        assertMatchesGolden("multiple-field-functions")
    }

    @Test
    fun `golden - skips functions without Field annotation`() {
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { returnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val fieldFn = mockFunction("getTitle", annotations = listOf(mockFieldAnnotation()))
        every { fieldFn.returnType } returns returnTypeRef
        every { fieldFn.modifiers } returns setOf(Modifier.SUSPEND)

        val helperFn = mockFunction("helperMethod", annotations = emptyList())

        val found = mockFoundTypeController(functions = sequenceOf(fieldFn, helperFn))
        generator.generate(listOf(found))
        assertMatchesGolden("skip-non-field-functions")
    }

    @Test
    fun `golden - batch function with property batch key`() {
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

        // Batch parameter: Batch<UUID, Author>
        val batchKeyType = ClassName("kotlin.uuid", "Uuid")
        val batchReturnType = ClassName("com.example", "Author")

        val batchParamTypeRef = mockk<KSTypeReference>()
        val batchParamType = mockk<KSType>()
        val batchDecl = mockk<KSClassDeclaration>()
        every { batchParamTypeRef.resolve() } returns batchParamType
        every { batchParamType.declaration } returns batchDecl
        every { batchDecl.qualifiedName } returns mockKSName(Types.Batch.canonicalName)

        val keyTypeArg = mockk<KSTypeArgument>()
        val returnTypeArg = mockk<KSTypeArgument>()
        val keyTypeRef = mockk<KSTypeReference>()
        val returnTypeArgRef = mockk<KSTypeReference>()
        val resolvedReturnType = mockk<KSType>()
        every { batchParamType.arguments } returns listOf(keyTypeArg, returnTypeArg)
        every { keyTypeArg.type } returns keyTypeRef
        every { returnTypeArg.type } returns returnTypeArgRef
        every { keyTypeRef.toTypeName(any()) } returns batchKeyType
        every { keyTypeRef.toTypeName() } returns batchKeyType
        every { returnTypeArgRef.toTypeName(any()) } returns batchReturnType
        every { returnTypeArgRef.toTypeName() } returns batchReturnType
        every { returnTypeArgRef.resolve() } returns resolvedReturnType
        every { resolvedReturnType.toTypeName(any()) } returns batchReturnType
        every { resolvedReturnType.toTypeName() } returns batchReturnType
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
        assertMatchesGolden("batch-function-property-key")
    }

    @Test
    fun `golden - mixed suspend and property fetchers`() {
        val stringReturnTypeRef = mockk<KSTypeReference>()
        every { stringReturnTypeRef.toTypeName(any()) } returns ClassName("kotlin", "String")
        every { stringReturnTypeRef.toTypeName() } returns ClassName("kotlin", "String")

        val sourceParam = mockTypedParameter("content", ClassName("com.example", "Content"))
        val idParam = mockTypedParameter("id", ClassName("kotlin", "String"), nullable = false)

        // Suspend function with source + arg
        val fn1 = mockFunction("getField", annotations = listOf(mockFieldAnnotation()), parameters = listOf(sourceParam, idParam))
        every { fn1.returnType } returns stringReturnTypeRef
        every { fn1.modifiers } returns setOf(Modifier.SUSPEND)

        // Non-suspend property function (no params)
        val fn2 = mockFunction("getName", annotations = listOf(mockFieldAnnotation()))
        every { fn2.returnType } returns stringReturnTypeRef
        every { fn2.modifiers } returns emptySet()

        val found = mockFoundTypeController(functions = sequenceOf(fn1, fn2))
        generator.generate(listOf(found))
        assertMatchesGolden("mixed-suspend-property")
    }
}
