package bosca.ksp.generator.db

import bosca.ksp.visitors.FoundRepository
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueArgument
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive tests for [RepositoryGenerator] covering all code paths
 * for 100% line and branch coverage via mocked KSP symbols.
 */
class RepositoryGeneratorTest {

    private val outputStream = ByteArrayOutputStream()
    private val codeGenerator = mockk<CodeGenerator>(relaxed = true) {
        every { createNewFile(any(), any(), any(), any()) } returns outputStream
    }
    private val logger = mockk<KSPLogger>(relaxed = true)
    private val generator = RepositoryGenerator(codeGenerator, logger)

    @Before
    fun setup() {
        outputStream.reset()
        mockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
    }

    @After
    fun tearDown() {
        unmockkStatic("com.squareup.kotlinpoet.ksp.KsTypesKt")
    }

    // ── Mock Helpers ──

    private fun mockKSName(value: String): KSName {
        val name = mockk<KSName>()
        every { name.asString() } returns value
        every { name.getShortName() } returns value.substringAfterLast('.')
        every { name.getQualifier() } returns value.substringBeforeLast('.', "")
        return name
    }

    private fun mockResolver(collectionAssignable: Boolean = false): Resolver {
        val resolver = mockk<Resolver>(relaxed = true)
        val collectionDecl = mockk<KSClassDeclaration>()
        val collectionType = mockk<KSType>()
        every { resolver.getKSNameFromString(any()) } answers { mockKSName(firstArg()) }
        every { resolver.getClassDeclarationByName(any()) } returns collectionDecl
        every { collectionDecl.asStarProjectedType() } returns collectionType
        every { collectionType.isAssignableFrom(any()) } returns collectionAssignable
        return resolver
    }

    private fun mockQueryAnnotation(sql: String): KSAnnotation {
        val annotation = mockk<KSAnnotation>()
        every { annotation.shortName } returns mockKSName("Query")
        val valueArg = mockk<KSValueArgument>()
        every { valueArg.name } returns mockKSName("value")
        every { valueArg.value } returns sql
        every { annotation.arguments } returns listOf(valueArg)
        return annotation
    }

    /**
     * Creates a mock KSClassDeclaration for a simple (non-model) type like String, Int, UUID, etc.
     * The key difference from model declarations: no properties, package inferred from typeName.
     */
    private fun mockSimpleClassDecl(typeName: TypeName, classKind: ClassKind = ClassKind.CLASS): KSClassDeclaration {
        val decl = mockk<KSClassDeclaration>(relaxed = true)
        every { decl.classKind } returns classKind
        every { decl.annotations } returns emptySequence()
        every { decl.getAllProperties() } returns emptySequence()
        every { decl.getAllFunctions() } returns emptySequence()
        every { decl.primaryConstructor } returns null
        if (typeName is ClassName) {
            every { decl.simpleName } returns mockKSName(typeName.simpleName)
            every { decl.packageName } returns mockKSName(typeName.packageName)
            every { decl.qualifiedName } returns mockKSName(typeName.canonicalName)
        }
        return decl
    }

    /**
     * Creates a mock KSDeclaration (NOT KSClassDeclaration) so that ReturnType
     * treats it as non-model (the `if (returnType.type !is KSClassDeclaration)` check).
     */
    private fun mockNonClassDecl(typeName: TypeName): KSDeclaration {
        val decl = mockk<KSDeclaration>(relaxed = true)
        if (typeName is ClassName) {
            every { decl.simpleName } returns mockKSName(typeName.simpleName)
            every { decl.packageName } returns mockKSName(typeName.packageName)
            every { decl.qualifiedName } returns mockKSName(typeName.canonicalName)
        }
        return decl
    }

    /**
     * Creates a mock KSTypeReference that resolves to the given typeName.
     */
    private fun mockTypeRef(
        typeName: TypeName,
        declaration: KSDeclaration? = null,
        isNullable: Boolean = false,
        typeArguments: List<KSTypeArgument> = emptyList()
    ): KSTypeReference {
        val typeRef = mockk<KSTypeReference>()
        val ksType = mockk<KSType>()
        every { typeRef.resolve() } returns ksType
        every { typeRef.toTypeName(any()) } returns typeName
        every { typeRef.toTypeName() } returns typeName
        every { ksType.toTypeName(any()) } returns typeName
        every { ksType.toTypeName() } returns typeName
        every { ksType.isMarkedNullable } returns isNullable
        every { ksType.arguments } returns typeArguments
        val decl = declaration ?: mockSimpleClassDecl(typeName)
        every { ksType.declaration } returns decl
        return typeRef
    }

    private fun mockParameter(name: String, typeRef: KSTypeReference): KSValueParameter {
        val param = mockk<KSValueParameter>()
        every { param.name } returns mockKSName(name)
        every { param.type } returns typeRef
        return param
    }

    private fun mockFunction(
        name: String,
        annotations: List<KSAnnotation> = emptyList(),
        parameters: List<KSValueParameter> = emptyList(),
        returnTypeRef: KSTypeReference? = null
    ): KSFunctionDeclaration {
        val fn = mockk<KSFunctionDeclaration>()
        every { fn.simpleName } returns mockKSName(name)
        every { fn.annotations } returns annotations.asSequence()
        every { fn.parameters } returns parameters
        every { fn.parentDeclaration } returns null
        if (returnTypeRef != null) {
            every { fn.returnType } returns returnTypeRef
        }
        return fn
    }

    data class ModelProperty(
        val name: String,
        val typeName: TypeName,
        val nullable: Boolean = false,
        val hasDbMapper: Boolean = false,
        val dbMapperQualifiedName: String = "",
        val hasColumnName: Boolean = false,
        val columnName: String = "",
        val isIgnored: Boolean = false
    )

    /**
     * Creates a full model class declaration with properties, primary constructor, and
     * optionally a DbConstructor-annotated constructor.
     */
    private fun mockModelClassDecl(
        qualifiedName: String,
        properties: List<ModelProperty>,
        useDbConstructor: Boolean = false
    ): KSClassDeclaration {
        val decl = mockk<KSClassDeclaration>(relaxed = true)
        val simpleName = qualifiedName.substringAfterLast('.')
        val packageName = qualifiedName.substringBeforeLast('.')
        every { decl.classKind } returns ClassKind.CLASS
        every { decl.simpleName } returns mockKSName(simpleName)
        every { decl.qualifiedName } returns mockKSName(qualifiedName)
        every { decl.packageName } returns mockKSName(packageName)
        every { decl.annotations } returns emptySequence()

        // Properties
        val props = properties.filter { !it.isIgnored }.map { mp ->
            val prop = mockk<KSPropertyDeclaration>()
            every { prop.simpleName } returns mockKSName(mp.name)

            // Property annotations (ColumnName, Ignore)
            val propAnnotations = mutableListOf<KSAnnotation>()
            if (mp.hasColumnName) {
                val colAnnotation = mockk<KSAnnotation>()
                every { colAnnotation.shortName } returns mockKSName("ColumnName")
                val colArg = mockk<KSValueArgument>()
                every { colArg.value } returns mp.columnName
                every { colAnnotation.arguments } returns listOf(colArg)
                propAnnotations.add(colAnnotation)
            }
            every { prop.annotations } returns propAnnotations.asSequence()

            // Property type
            val propTypeRef = mockk<KSTypeReference>()
            val propKsType = mockk<KSType>()
            every { propTypeRef.resolve() } returns propKsType
            every { propTypeRef.toTypeName(any()) } returns mp.typeName
            every { propTypeRef.toTypeName() } returns mp.typeName
            every { propKsType.isMarkedNullable } returns mp.nullable

            // Mock toTypeName and arguments on the KSType so static extension doesn't fail
            every { propKsType.toTypeName(any()) } returns mp.typeName
            every { propKsType.toTypeName() } returns mp.typeName
            every { propKsType.arguments } returns emptyList()

            // Property type declaration with possible DbMapper
            val propTypeDecl = mockk<KSClassDeclaration>()
            if (mp.hasDbMapper) {
                val dbMapperType = mockk<KSType>()
                val dbMapperDecl = mockk<KSClassDeclaration>()
                every { dbMapperType.declaration } returns dbMapperDecl
                every { dbMapperDecl.qualifiedName } returns mockKSName(mp.dbMapperQualifiedName)
                val dbMapperTypeName = ClassName(mp.dbMapperQualifiedName.substringBeforeLast('.'), mp.dbMapperQualifiedName.substringAfterLast('.'))
                every { dbMapperType.toTypeName(any()) } returns dbMapperTypeName
                every { dbMapperType.toTypeName() } returns dbMapperTypeName
                every { dbMapperType.arguments } returns emptyList()
                every { dbMapperType.isMarkedNullable } returns false
                val dbMapperAnnotation = mockk<KSAnnotation>()
                every { dbMapperAnnotation.shortName } returns mockKSName("DbMapper")
                val dbMapperArg = mockk<KSValueArgument>()
                every { dbMapperArg.value } returns dbMapperType
                every { dbMapperAnnotation.arguments } returns listOf(dbMapperArg)
                every { propTypeDecl.annotations } returns sequenceOf(dbMapperAnnotation)
            } else {
                every { propTypeDecl.annotations } returns emptySequence()
            }
            every { propKsType.declaration } returns propTypeDecl
            every { prop.type } returns propTypeRef
            prop
        }

        // Also include ignored properties
        val ignoredProps = properties.filter { it.isIgnored }.map { mp ->
            val prop = mockk<KSPropertyDeclaration>()
            every { prop.simpleName } returns mockKSName(mp.name)
            val ignoreAnnotation = mockk<KSAnnotation>()
            every { ignoreAnnotation.shortName } returns mockKSName("Ignore")
            every { prop.annotations } returns sequenceOf(ignoreAnnotation)
            prop
        }

        every { decl.getAllProperties() } returns (props + ignoredProps).asSequence()

        // Constructor - mirror the non-ignored properties
        val constructorParams = properties.filter { !it.isIgnored }.map { mp ->
            val cp = mockk<KSValueParameter>()
            every { cp.name } returns mockKSName(mp.name)
            val cpTypeRef = mockk<KSTypeReference>()
            val cpKsType = mockk<KSType>()
            every { cpTypeRef.resolve() } returns cpKsType
            every { cpTypeRef.toTypeName(any()) } returns mp.typeName
            every { cpTypeRef.toTypeName() } returns mp.typeName
            // Mock toTypeName and arguments on the constructor param KSType
            every { cpKsType.toTypeName(any()) } returns mp.typeName
            every { cpKsType.toTypeName() } returns mp.typeName
            every { cpKsType.arguments } returns emptyList()

            // Constructor param type declaration - also needs DbMapper if present
            val cpTypeDecl = mockk<KSClassDeclaration>()
            if (mp.hasDbMapper) {
                val dbMapperType = mockk<KSType>()
                val dbMapperDecl = mockk<KSClassDeclaration>()
                every { dbMapperType.declaration } returns dbMapperDecl
                every { dbMapperDecl.qualifiedName } returns mockKSName(mp.dbMapperQualifiedName)
                val dbMapperTypeName = ClassName(mp.dbMapperQualifiedName.substringBeforeLast('.'), mp.dbMapperQualifiedName.substringAfterLast('.'))
                every { dbMapperType.toTypeName(any()) } returns dbMapperTypeName
                every { dbMapperType.toTypeName() } returns dbMapperTypeName
                every { dbMapperType.arguments } returns emptyList()
                every { dbMapperType.isMarkedNullable } returns false
                val dbMapperAnnotation = mockk<KSAnnotation>()
                every { dbMapperAnnotation.shortName } returns mockKSName("DbMapper")
                val dbMapperArg = mockk<KSValueArgument>()
                every { dbMapperArg.value } returns dbMapperType
                every { dbMapperAnnotation.arguments } returns listOf(dbMapperArg)
                every { cpTypeDecl.annotations } returns sequenceOf(dbMapperAnnotation)
            } else {
                every { cpTypeDecl.annotations } returns emptySequence()
            }
            every { cpKsType.declaration } returns cpTypeDecl
            every { cp.type } returns cpTypeRef
            cp
        }

        val constructor = mockk<KSFunctionDeclaration>()
        every { constructor.parameters } returns constructorParams
        every { constructor.simpleName } returns mockKSName("<init>")
        every { constructor.parentDeclaration } returns decl

        if (useDbConstructor) {
            val dbCtorAnnotation = mockk<KSAnnotation>()
            every { dbCtorAnnotation.shortName } returns mockKSName("DbConstructor")
            every { constructor.annotations } returns sequenceOf(dbCtorAnnotation)
        } else {
            every { constructor.annotations } returns emptySequence()
        }

        every { decl.primaryConstructor } returns constructor
        every { decl.getAllFunctions() } returns sequenceOf(constructor)
        return decl
    }

    /**
     * Creates a model class with an extra constructor param that is NOT in the model's properties.
     * This tests the `return@forEach` / `return@buildString` paths in toConstructor.
     */
    private fun mockModelWithExtraConstructorParam(
        qualifiedName: String,
        properties: List<ModelProperty>,
        extraParamName: String,
        extraParamType: TypeName
    ): KSClassDeclaration {
        val decl = mockModelClassDecl(qualifiedName, properties)
        // Now add an extra constructor param
        val existing = decl.getAllFunctions().first().parameters
        val extraParam = mockk<KSValueParameter>()
        every { extraParam.name } returns mockKSName(extraParamName)
        val extraTypeRef = mockk<KSTypeReference>()
        val extraKsType = mockk<KSType>()
        every { extraTypeRef.resolve() } returns extraKsType
        every { extraTypeRef.toTypeName(any()) } returns extraParamType
        every { extraTypeRef.toTypeName() } returns extraParamType
        val extraTypeDecl = mockk<KSClassDeclaration>()
        every { extraTypeDecl.annotations } returns emptySequence()
        every { extraKsType.declaration } returns extraTypeDecl
        every { extraParam.type } returns extraTypeRef

        val newParams = existing + extraParam
        val constructor = decl.getAllFunctions().first()
        every { constructor.parameters } returns newParams
        return decl
    }

    /**
     * Creates a return type reference suitable for the ReturnType class.
     * When classDecl is provided (for model returns), uses it.
     * Otherwise, creates a non-KSClassDeclaration so ReturnType treats it as primitive/non-model.
     */
    private fun mockReturnTypeRef(
        typeName: TypeName,
        isNullable: Boolean = false,
        classDecl: KSClassDeclaration? = null
    ): KSTypeReference {
        val decl: KSDeclaration = classDecl ?: mockNonClassDecl(typeName)
        return mockTypeRef(typeName, decl, isNullable)
    }

    /**
     * Creates a return type reference for a collection type (List or Set).
     * The inner type can be primitive or a model class.
     */
    /**
     * Creates a return type reference for a collection type (List or Set).
     * IMPORTANT: The resolver must have been created with `collectionAssignable = true`
     * so that `collectionType.isAssignableFrom()` returns true during ReturnType init.
     */
    private fun mockCollectionReturnTypeRef(
        collectionTypeName: TypeName,
        innerDecl: KSDeclaration
    ): KSTypeReference {
        val outerDecl = mockk<KSClassDeclaration>(relaxed = true)
        every { outerDecl.annotations } returns emptySequence()
        val pkg = when (collectionTypeName) {
            is ClassName -> collectionTypeName.packageName
            is com.squareup.kotlinpoet.ParameterizedTypeName -> collectionTypeName.rawType.packageName
            else -> "kotlin.collections"
        }
        every { outerDecl.packageName } returns mockKSName(pkg)

        val innerKsType = mockk<KSType>()
        val innerTypeRef = mockk<KSTypeReference>()
        every { innerTypeRef.resolve() } returns innerKsType
        every { innerKsType.declaration } returns innerDecl
        val typeArg = mockk<KSTypeArgument>()
        every { typeArg.type } returns innerTypeRef

        val returnKsType = mockk<KSType>()
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.resolve() } returns returnKsType
        every { returnTypeRef.toTypeName(any()) } returns collectionTypeName
        every { returnTypeRef.toTypeName() } returns collectionTypeName
        every { returnKsType.toTypeName(any()) } returns collectionTypeName
        every { returnKsType.toTypeName() } returns collectionTypeName
        every { returnKsType.isMarkedNullable } returns false
        every { returnKsType.declaration } returns outerDecl
        every { returnKsType.arguments } returns listOf(typeArg)
        return returnTypeRef
    }

    private fun mockFoundRepository(
        packageName: String = "com.example",
        className: String = "TestRepository",
        functions: Sequence<KSFunctionDeclaration> = emptySequence()
    ): FoundRepository {
        val classDecl = mockk<KSClassDeclaration>()
        val file = mockk<KSFile>()
        every { classDecl.containingFile } returns file
        every { classDecl.getAllFunctions() } returns functions
        every { file.packageName } returns mockKSName(packageName)
        every { file.fileName } returns "$className.kt"
        every { file.filePath } returns "$packageName/$className.kt"
        every { file.annotations } returns emptySequence()
        return FoundRepository(
            repository = ClassName(packageName, className),
            provider = ClassName(packageName, "${className}Provider"),
            providers = ClassName(packageName, "${className}Impl"),
            classDeclaration = classDecl
        )
    }

    private fun prepareGenerator(resolver: Resolver = mockResolver()) {
        generator.prepare(resolver)
    }

    private fun generatedCode(): String = outputStream.toString(Charsets.UTF_8.name())

    // ── prepare() ──

    @Test
    fun `prepare sets resolver and collectionType`() {
        prepareGenerator()
    }

    @Test
    fun `prepare throws when Collection class not found`() {
        val resolver = mockk<Resolver>(relaxed = true)
        every { resolver.getKSNameFromString(any()) } answers { mockKSName(firstArg()) }
        every { resolver.getClassDeclarationByName(any()) } returns null
        try {
            generator.prepare(resolver)
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Collection class not found"))
        }
    }

    // ── generate() empty ──

    @Test
    fun `generate with empty items does nothing`() {
        prepareGenerator()
        generator.generate(emptyList())
    }

    // ── generate() with SELECT returning primitive types (non-model path in toQuery) ──

    private fun testSelectReturningPrimitive(paramTypeName: ClassName, sql: String = "SELECT val FROM t WHERE p = :p") {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(paramTypeName)
        val queryAnnotation = mockQueryAnnotation(sql)
        // Use a simple Int param to avoid model detection issues with non-isPrimitive types
        val paramTypeRef = mockTypeRef(ClassName("kotlin", "Int"))
        val param = mockParameter("p", paramTypeRef)
        val fn = mockFunction("find", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    @Test fun `SELECT returning String`() = testSelectReturningPrimitive(ClassName("kotlin", "String"))
    @Test fun `SELECT returning Int`() = testSelectReturningPrimitive(ClassName("kotlin", "Int"))
    @Test fun `SELECT returning Long`() = testSelectReturningPrimitive(ClassName("kotlin", "Long"))
    @Test fun `SELECT returning Float`() = testSelectReturningPrimitive(ClassName("kotlin", "Float"))
    @Test fun `SELECT returning Double`() = testSelectReturningPrimitive(ClassName("kotlin", "Double"))
    @Test fun `SELECT returning Boolean`() = testSelectReturningPrimitive(ClassName("kotlin", "Boolean"))
    @Test fun `SELECT returning UUID`() = testSelectReturningPrimitive(ClassName("kotlin.uuid", "Uuid"))
    @Test fun `SELECT returning bosca UUID`() = testSelectReturningPrimitive(ClassName("bosca.serialization", "UUID"))
    @Test fun `SELECT returning ByteArray`() = testSelectReturningPrimitive(ClassName("kotlin", "ByteArray"))
    @Test fun `SELECT returning OffsetDateTime`() = testSelectReturningPrimitive(ClassName("java.time", "OffsetDateTime"))
    @Test fun `SELECT returning bosca OffsetDateTime`() = testSelectReturningPrimitive(ClassName("bosca.serialization", "OffsetDateTime"))
    @Test fun `SELECT returning LocalDateTime`() = testSelectReturningPrimitive(ClassName("java.time", "LocalDateTime"))
    @Test fun `SELECT returning bosca LocalDateTime`() = testSelectReturningPrimitive(ClassName("bosca.serialization", "LocalDateTime"))
    @Test fun `SELECT returning Instant`() = testSelectReturningPrimitive(ClassName("kotlin.time", "Instant"))
    @Test fun `SELECT returning JsonElement`() = testSelectReturningPrimitive(ClassName("kotlinx.serialization.json", "JsonElement"))

    @Test
    fun `SELECT returning nullable primitive`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val typeName = ClassName("kotlin", "String").copy(nullable = true)
        val returnTypeRef = mockReturnTypeRef(typeName, isNullable = true)
        val queryAnnotation = mockQueryAnnotation("SELECT name FROM t WHERE id = :id")
        val paramTypeRef = mockTypeRef(ClassName("kotlin", "Int"))
        val param = mockParameter("id", paramTypeRef)
        val fn = mockFunction("find", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Nullable should use null", code.contains("null"))
    }

    // ── Bind mapper types via model parameter ──

    /**
     * Tests defaultBindMappers for a specific type by wrapping it in a model parameter.
     * This uses the single-param model path to exercise the bind mapper for the type.
     */
    private fun testBindType(paramTypeName: TypeName) {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val modelDecl = mockModelClassDecl("com.example.BindModel", listOf(
            ModelProperty("p", paramTypeName),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "BindModel"), modelDecl)
        val param = mockParameter("model", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (p) VALUES (:p)")
        val fn = mockFunction("doInsert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    @Test fun `bind String param`() = testBindType(ClassName("kotlin", "String"))
    @Test fun `bind Int param`() = testBindType(ClassName("kotlin", "Int"))
    @Test fun `bind Long param`() = testBindType(ClassName("kotlin", "Long"))
    @Test fun `bind Float param`() = testBindType(ClassName("kotlin", "Float"))
    @Test fun `bind Double param`() = testBindType(ClassName("kotlin", "Double"))
    @Test fun `bind Boolean param`() = testBindType(ClassName("kotlin", "Boolean"))
    @Test fun `bind ByteArray param`() = testBindType(ClassName("kotlin", "ByteArray"))
    @Test fun `bind UUID param`() = testBindType(ClassName("kotlin.uuid", "Uuid"))
    @Test fun `bind bosca UUID param`() = testBindType(ClassName("bosca.serialization", "UUID"))
    @Test fun `bind OffsetDateTime param`() = testBindType(ClassName("java.time", "OffsetDateTime"))
    @Test fun `bind bosca OffsetDateTime param`() = testBindType(ClassName("bosca.serialization", "OffsetDateTime"))
    @Test fun `bind LocalDateTime param`() = testBindType(ClassName("java.time", "LocalDateTime"))
    @Test fun `bind bosca LocalDateTime param`() = testBindType(ClassName("bosca.serialization", "LocalDateTime"))
    @Test fun `bind Instant param`() = testBindType(ClassName("kotlin.time", "Instant"))
    @Test fun `bind JsonElement param`() = testBindType(ClassName("kotlinx.serialization.json", "JsonElement"))

    @Test
    fun `bind List param`() {
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
        testBindType(listType)
    }

    @Test
    fun `bind enum param via model`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        // Create model with enum property - no need to override mock chains
        val modelDecl = mockModelClassDecl("com.example.EnumModel", listOf(
            ModelProperty("p", ClassName("com.example", "MyEnum")),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "EnumModel"), modelDecl)
        val param = mockParameter("model", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (p) VALUES (:p)")
        val fn = mockFunction("doInsert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        // Set up resolver to return enum declaration for the type name lookup in defaultBindMappers
        val enumDecl = mockSimpleClassDecl(ClassName("com.example", "MyEnum"), ClassKind.ENUM_CLASS)
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.MyEnum" }) } returns enumDecl
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should reference enum", generatedCode().contains("enum"))
    }

    @Test
    fun `bind serializable param via model`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        // Create model with serializable property - no need to override mock chains
        val modelDecl = mockModelClassDecl("com.example.SerModel", listOf(
            ModelProperty("p", ClassName("com.example", "Payload")),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "SerModel"), modelDecl)
        val param = mockParameter("model", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (p) VALUES (:p)")
        val fn = mockFunction("doInsert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        // Set up resolver to return serializable declaration for the type name lookup
        val serDecl = mockSimpleClassDecl(ClassName("com.example", "Payload"))
        val serAnnotation = mockk<KSAnnotation>()
        every { serAnnotation.shortName } returns mockKSName("Serializable")
        every { serDecl.annotations } returns sequenceOf(serAnnotation)
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Payload" }) } returns serDecl
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should reference serializable", generatedCode().contains("serializable"))
    }

    // ── INSERT returning Unit ──

    @Test
    fun `INSERT returning Unit`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (name) VALUES (:name)")
        val paramTypeRef = mockTypeRef(ClassName("kotlin", "String"))
        val param = mockParameter("name", paramTypeRef)
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Unit return should call execute()", code.contains("stmt.execute()"))
        assertTrue("INSERT should mark commit", code.contains("markNeedsCommitOrRollback"))
    }

    // ── UPDATE returning Int ──

    @Test
    fun `UPDATE returning Int`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Int"))
        val queryAnnotation = mockQueryAnnotation("UPDATE t SET name = :name WHERE id = :id")
        val p1 = mockParameter("name", mockTypeRef(ClassName("kotlin", "String")))
        val p2 = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("update", listOf(queryAnnotation), listOf(p1, p2), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("UPDATE should mark commit", generatedCode().contains("markNeedsCommitOrRollback"))
    }

    // ── DELETE returning Unit ──

    @Test
    fun `DELETE returning Unit`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("DELETE FROM t WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("delete", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("DELETE should mark commit", generatedCode().contains("markNeedsCommitOrRollback"))
    }

    // ── SELECT returning model with primary constructor ──

    @Test
    fun `SELECT returning model with primary constructor`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty("name", ClassName("kotlin", "String")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "User"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id, name FROM users WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Should construct User", code.contains("User"))
    }

    // ── SELECT returning model with DbConstructor ──

    @Test
    fun `SELECT returning model with DbConstructor`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty("name", ClassName("kotlin", "String")),
        ), useDbConstructor = true)
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "User"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id, name FROM users WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── SELECT returning nullable model ──

    @Test
    fun `SELECT returning nullable model`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "User").copy(nullable = true), isNullable = true, classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM users WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Nullable model should have null path", generatedCode().contains("null"))
    }

    // ── SELECT returning List of models ──

    @Test
    fun `SELECT returning List of models`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val innerDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty("name", ClassName("kotlin", "String")),
        ))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("com.example", "User"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id, name FROM users")
        val fn = mockFunction("findAll", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Should use mutableListOf", code.contains("mutableListOf"))
        assertTrue("Should use results.add", code.contains("results.add"))
    }

    // ── SELECT returning Set of models ──

    @Test
    fun `SELECT returning Set of models`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val innerDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
        ))
        // The inner type's simpleName needs to be "Set" for the SET branch
        every { innerDecl.simpleName } returns mockKSName("Set")
        val setType = ClassName("kotlin.collections", "Set").parameterizedBy(ClassName("com.example", "User"))
        val returnTypeRef = mockCollectionReturnTypeRef(setType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM users")
        val fn = mockFunction("findAll", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should use mutableSetOf", generatedCode().contains("mutableSetOf"))
    }

    // ── SELECT returning List of primitives (non-model collection) ──

    @Test
    fun `SELECT returning List of primitives`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        // Use non-KSClassDeclaration so toQuery enters non-model path
        val innerDecl = mockNonClassDecl(ClassName("kotlin", "String"))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT name FROM users")
        val fn = mockFunction("findAllNames", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── SELECT returning Set of primitives (non-model collection with SET type) ──

    @Test
    fun `SELECT returning Set of primitives`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        // Use non-KSClassDeclaration; simpleName "Set" triggers SET collection type
        val innerDecl = mockNonClassDecl(ClassName("kotlin", "String"))
        every { innerDecl.simpleName } returns mockKSName("Set")
        val setType = ClassName("kotlin.collections", "Set").parameterizedBy(ClassName("kotlin", "String"))
        val returnTypeRef = mockCollectionReturnTypeRef(setType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT name FROM users")
        val fn = mockFunction("findAllNames", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should use mutableSetOf", generatedCode().contains("mutableSetOf"))
    }

    // ── Scalar projections where the element resolves as a KSClassDeclaration ──
    // Real KSP resolves kotlin.String (and other scalars) to a KSClassDeclaration with a
    // synthesizable DatabaseModel, unlike mockNonClassDecl above. These must still take the
    // column-mapping path — the row-model constructor path emits a no-arg `String()` per row,
    // silently replacing every value with "".

    @Test
    fun `SELECT returning List of String with class declaration element maps the column`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val innerDecl = mockSimpleClassDecl(ClassName("kotlin", "String"))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT name FROM users")
        val fn = mockFunction("findAllNames", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Should map the column via the string mapper", code.contains("mappers.string.map"))
        assertFalse("Must not construct the element from no args", code.contains("results.add(String("))
    }

    @Test
    fun `SELECT returning List of bosca UUID with class declaration element maps the column`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val innerDecl = mockSimpleClassDecl(ClassName("bosca.serialization", "UUID"))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("bosca.serialization", "UUID"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM users")
        val fn = mockFunction("findAllIds", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Should map the column via the uuid mapper", code.contains("mappers.uuid.map"))
        assertFalse("Must not construct the element from no args", code.contains("results.add(UUID("))
    }

    @Test
    fun `SELECT returning single String with class declaration uses scalar path`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val classDecl = mockSimpleClassDecl(ClassName("kotlin", "String"))
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "String"), classDecl = classDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT name FROM users WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findName", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should map the column via the string mapper", generatedCode().contains("mappers.string.map"))
    }

    @Test
    fun `SELECT returning List of kotlin Uuid with class declaration element maps the column`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val innerDecl = mockSimpleClassDecl(ClassName("kotlin.uuid", "Uuid"))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin.uuid", "Uuid"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM users")
        val fn = mockFunction("findAllIds", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Should map the column via the uuid mapper", code.contains("mappers.uuid.map"))
        assertFalse("Must not construct the element from no args", code.contains("results.add(Uuid("))
    }

    @Test
    fun `SELECT returning List of ByteArray with class declaration element maps the column`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val innerDecl = mockSimpleClassDecl(ClassName("kotlin", "ByteArray"))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "ByteArray"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT payload FROM blobs")
        val fn = mockFunction("findAllPayloads", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Should map the column via the byteArray mapper", code.contains("mappers.byteArray.map"))
        assertFalse("Must not construct the element from no args", code.contains("results.add(ByteArray("))
    }

    @Test
    fun `SELECT returning single ByteArray with class declaration uses scalar path`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val classDecl = mockSimpleClassDecl(ClassName("kotlin", "ByteArray"))
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "ByteArray"), classDecl = classDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT payload FROM blobs WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findPayload", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should map the column via the byteArray mapper", generatedCode().contains("mappers.byteArray.map"))
    }

    @Test
    fun `INSERT returning List of String with class declaration element maps the column`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val innerDecl = mockSimpleClassDecl(ClassName("kotlin", "String"))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (name) VALUES (:name) RETURNING name")
        val param = mockParameter("name", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("insertReturningNames", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Should map the column via the string mapper", code.contains("mappers.string.map"))
        assertFalse("Must not construct the element from no args", code.contains("results.add(String("))
    }

    // ── Model parameter (single param, model class) ──

    @Test
    fun `INSERT with model parameter`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("name", ClassName("kotlin", "String")),
            ModelProperty("age", ClassName("kotlin", "Int")),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "User"), modelDecl)
        val param = mockParameter("user", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO users (name, age) VALUES (:name, :age)")
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── Model parameter with DbMapper ──

    @Test
    fun `INSERT with model parameter having dbMapper property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val modelDecl = mockModelClassDecl("com.example.MyModel", listOf(
            ModelProperty("custom", ClassName("com.example", "CustomType"), hasDbMapper = true, dbMapperQualifiedName = "com.example.CustomMapper"),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "MyModel"), modelDecl)
        val param = mockParameter("model", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (custom) VALUES (:custom)")
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should use custom mapper", generatedCode().contains("CustomMapper"))
    }

    // ── Model parameter with ParameterizedTypeName property (List<String>) ──

    @Test
    fun `INSERT with model parameter having List property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
        val modelDecl = mockModelClassDecl("com.example.MyModel", listOf(
            ModelProperty("tags", listType),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "MyModel"), modelDecl)
        val param = mockParameter("model", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (tags) VALUES (:tags)")
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── DbMapper on parameter type (no model) ──

    @Test
    fun `INSERT with parameter having DbMapper annotation`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (v, x) VALUES (:p, :q)")

        val dbMapperType = mockk<KSType>()
        val dbMapperDecl = mockk<KSClassDeclaration>()
        every { dbMapperType.declaration } returns dbMapperDecl
        every { dbMapperDecl.qualifiedName } returns mockKSName("com.example.CustomMapper")
        val dbMapperAnnotation = mockk<KSAnnotation>()
        every { dbMapperAnnotation.shortName } returns mockKSName("DbMapper")
        val dbMapperArg = mockk<KSValueArgument>()
        every { dbMapperArg.value } returns dbMapperType
        every { dbMapperAnnotation.arguments } returns listOf(dbMapperArg)

        // Use a primitive type (String) but with @DbMapper annotation on its declaration
        // so the multi-param check passes (isPrimitive is true) while the no-model
        // DbMapper path is exercised in toBindings
        val paramDecl = mockSimpleClassDecl(ClassName("kotlin", "String"))
        every { paramDecl.annotations } returns sequenceOf(dbMapperAnnotation)
        val paramTypeRef = mockTypeRef(ClassName("kotlin", "String"), paramDecl)
        val param = mockParameter("p", paramTypeRef)
        val param2 = mockParameter("q", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param, param2), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should use custom mapper", generatedCode().contains("CustomMapper"))
    }

    // ── Multiple model params error ──

    @Test
    fun `errors on multiple non-primitive non-serializable parameters`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val m1 = mockSimpleClassDecl(ClassName("com.example", "M1"))
        val m2 = mockSimpleClassDecl(ClassName("com.example", "M2"))
        val p1 = mockParameter("m1", mockTypeRef(ClassName("com.example", "M1"), m1))
        val p2 = mockParameter("m2", mockTypeRef(ClassName("com.example", "M2"), m2))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (a, b) VALUES (:a, :b)")
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(p1, p2), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("do not support multiple model parameters"))
        }
    }

    @Test
    fun `allows primitive collection with another parameter`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val uuidType = ClassName("bosca.serialization", "UUID")
        val profileIdsType = ClassName("kotlin.collections", "List").parameterizedBy(uuidType)
        val metadataId = mockParameter("metadataId", mockTypeRef(uuidType))
        val profileIds = mockParameter("profileIds", mockTypeRef(profileIdsType))
        val queryAnnotation = mockQueryAnnotation(
            "DELETE FROM progress WHERE metadata_id = :metadataId AND profile_id = ANY(:profileIds)"
        )
        val fn = mockFunction(
            "deleteByMetadataIdAndProfileIds",
            listOf(queryAnnotation),
            listOf(metadataId, profileIds),
            returnTypeRef,
        )
        val repo = mockFoundRepository(functions = sequenceOf(fn))

        generator.generate(listOf(repo))

        val code = generatedCode()
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should bind the UUID collection as an array", code.contains("profileIds.toTypedArray()"))
    }

    // ── Failed query parse error ──

    @Test
    fun `errors on failed query parse`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "String"))
        val annotation = mockk<KSAnnotation>()
        every { annotation.shortName } returns mockKSName("Query")
        val valueArg = mockk<KSValueArgument>()
        every { valueArg.name } returns mockKSName("value")
        every { valueArg.value } returns "INVALID SQL"
        every { annotation.arguments } returns listOf(valueArg)
        val fn = mockFunction("bad", listOf(annotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("failed to parse query"))
            assertTrue(e.message!!.contains("bad"))
        }
    }

    // ── Functions without @Query are skipped ──

    @Test
    fun `generate skips functions without Query annotation`() {
        prepareGenerator()
        val fn = mockFunction("notAQuery")
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── UPDATE returning model with DbConstructor ──

    @Test
    fun `UPDATE returning model with DbConstructor`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty("name", ClassName("kotlin", "String")),
        ), useDbConstructor = true)
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "User"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("UPDATE users SET name = :name WHERE id = :id RETURNING *")
        val p1 = mockParameter("name", mockTypeRef(ClassName("kotlin", "String")))
        val p2 = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("updateAndReturn", listOf(queryAnnotation), listOf(p1, p2), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── SELECT returning model with nullable property ──

    @Test
    fun `SELECT returning model with nullable property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty("email", ClassName("kotlin", "String").copy(nullable = true), nullable = true),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "User"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id, email FROM users WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── SELECT returning model with DbMapper on constructor property ──

    @Test
    fun `SELECT returning model with DbMapper property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("custom", ClassName("com.example", "CustomType"), hasDbMapper = true, dbMapperQualifiedName = "com.example.CustomMapper"),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT custom FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should use CustomMapper", generatedCode().contains("CustomMapper"))
    }

    // ── SELECT returning model with isPrimitive typeName (e.g. bosca.serialization.UUID) but model is non-null ──

    @Test
    fun `SELECT returning isPrimitive model type uses primitive query path`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("bosca.serialization.UUID", listOf(
            ModelProperty("value", ClassName("kotlin", "String")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("bosca.serialization", "UUID"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM users WHERE name = :name")
        val param = mockParameter("name", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("findId", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── Constructor with param not in model columns (return@forEach / return@buildString) ──

    @Test
    fun `SELECT with constructor param not in model columns skips it`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelWithExtraConstructorParam(
            "com.example.Item",
            listOf(ModelProperty("id", ClassName("kotlin", "Int"))),
            "computed",
            ClassName("kotlin", "String")
        )
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── ParameterizedTypeName return type adds imports ──

    @Test
    fun `generate with ParameterizedTypeName return type adds imports`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val innerDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
        ))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("com.example", "User"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM users")
        val fn = mockFunction("findAll", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── ClassName return type adds import ──

    @Test
    fun `generate with ClassName return type adds import`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "String"))
        val queryAnnotation = mockQueryAnnotation("SELECT name FROM t")
        val fn = mockFunction("find", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── Enum map (in defaultMapMappers) via model return with enum property ──

    @Test
    fun `SELECT returning model with enum property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val enumDecl = mockSimpleClassDecl(ClassName("com.example", "Status"), ClassKind.ENUM_CLASS)
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Status" }) } returns enumDecl
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("status", ClassName("com.example", "Status")),
        ))
        // Override the constructor param type declaration to be the enum
        val constructor = modelDecl.getAllFunctions().first()
        val cp = constructor.parameters.first()
        val cpType = cp.type.resolve()
        every { cpType.declaration } returns enumDecl

        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT status FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── Serializable map (in defaultMapMappers) via model return with serializable property ──

    @Test
    fun `SELECT returning model with serializable property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val serDecl = mockSimpleClassDecl(ClassName("com.example", "Payload"))
        val serAnnotation = mockk<KSAnnotation>()
        every { serAnnotation.shortName } returns mockKSName("Serializable")
        every { serDecl.annotations } returns sequenceOf(serAnnotation)
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Payload" }) } returns serDecl

        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("data", ClassName("com.example", "Payload")),
        ))
        val constructor = modelDecl.getAllFunctions().first()
        val cp = constructor.parameters.first()
        val cpType = cp.type.resolve()
        every { cpType.declaration } returns serDecl

        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT data FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── Model return with Instant property ──

    @Test
    fun `SELECT returning model with Instant property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Event", listOf(
            ModelProperty("timestamp", ClassName("kotlin.time", "Instant")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Event"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT timestamp FROM events WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── Model return with ByteArray property ──

    @Test
    fun `SELECT returning model with ByteArray property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Blob", listOf(
            ModelProperty("data", ClassName("kotlin", "ByteArray")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Blob"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT data FROM blobs WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── Model return with List<String> property (tests List branch in defaultMapMappers) ──

    @Test
    fun `SELECT returning model with List property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
        val modelDecl = mockModelClassDecl("com.example.Tags", listOf(
            ModelProperty("values", listType),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Tags"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT values FROM tags WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should use toList for List map", generatedCode().contains("toList"))
    }

    // ── Model return with OffsetDateTime/LocalDateTime/JsonElement properties ──

    @Test
    fun `SELECT returning model with OffsetDateTime property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Event", listOf(
            ModelProperty("created", ClassName("java.time", "OffsetDateTime")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Event"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT created FROM events WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    @Test
    fun `SELECT returning model with bosca OffsetDateTime property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Event", listOf(
            ModelProperty("created", ClassName("bosca.serialization", "OffsetDateTime")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Event"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT created FROM events WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    @Test
    fun `SELECT returning model with LocalDateTime property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Event", listOf(
            ModelProperty("created", ClassName("java.time", "LocalDateTime")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Event"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT created FROM events WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    @Test
    fun `SELECT returning model with bosca LocalDateTime property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Event", listOf(
            ModelProperty("created", ClassName("bosca.serialization", "LocalDateTime")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Event"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT created FROM events WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    @Test
    fun `SELECT returning model with JsonElement property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Doc", listOf(
            ModelProperty("data", ClassName("kotlinx.serialization.json", "JsonElement")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Doc"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT data FROM docs WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    @Test
    fun `SELECT returning model with UUID property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("id", ClassName("kotlin.uuid", "Uuid")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    @Test
    fun `SELECT returning model with bosca UUID property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("id", ClassName("bosca.serialization", "UUID")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("bosca.serialization", "UUID")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── Collection return for UPDATE (isCollection in toUpdate - though rare) ──

    @Test
    fun `UPDATE returning model non-nullable`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "User"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("UPDATE users SET name = 'a' WHERE id = :id RETURNING *")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("update", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Non-nullable should error on null", code.contains("error"))
    }

    @Test
    fun `UPDATE returning nullable model`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "User").copy(nullable = true), isNullable = true, classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("UPDATE users SET name = 'a' WHERE id = :id RETURNING *")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("update", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Nullable should have null return path", generatedCode().contains("null"))
    }

    // ── UPDATE returning primitive non-Unit ──

    @Test
    fun `UPDATE returning primitive isPrimitive type`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Int"))
        val queryAnnotation = mockQueryAnnotation("UPDATE t SET v = :v RETURNING count(*)")
        val param = mockParameter("v", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("update", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── No param query ──

    @Test
    fun `SELECT with no parameters`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "String"))
        val queryAnnotation = mockQueryAnnotation("SELECT count(*) FROM users")
        val fn = mockFunction("count", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── Multiple repositories in one generate call ──

    @Test
    fun `generate with multiple repositories`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)

        val returnTypeRef1 = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val q1 = mockQueryAnnotation("INSERT INTO t (v) VALUES (:v)")
        val p1 = mockParameter("v", mockTypeRef(ClassName("kotlin", "String")))
        val fn1 = mockFunction("insert", listOf(q1), listOf(p1), returnTypeRef1)
        val repo1 = mockFoundRepository("com.example", "Repo1", sequenceOf(fn1))

        // Second repo uses a new output stream since the first one is done
        val os2 = ByteArrayOutputStream()
        every { codeGenerator.createNewFile(any(), any(), any(), any()) } returns os2
        val returnTypeRef2 = mockReturnTypeRef(ClassName("kotlin", "String"))
        val q2 = mockQueryAnnotation("SELECT name FROM t WHERE id = :id")
        val p2 = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn2 = mockFunction("find", listOf(q2), listOf(p2), returnTypeRef2)
        val repo2 = mockFoundRepository("com.example", "Repo2", sequenceOf(fn2))

        generator.generate(listOf(repo1, repo2))
        assertTrue("Expected output from second repo", os2.size() > 0)
    }

    // ── Model with ColumnName annotation ──

    @Test
    fun `INSERT with model having ColumnName annotation`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))

        // Model where "userName" maps to "user_name" column
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("userName", ClassName("kotlin", "String"), hasColumnName = true, columnName = "user_name"),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "User"), modelDecl)
        val param = mockParameter("user", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO users (user_name) VALUES (:userName)")
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── Model with Ignore annotation ──

    @Test
    fun `INSERT with model having Ignore annotation`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))

        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("name", ClassName("kotlin", "String")),
            ModelProperty("computed", ClassName("kotlin", "String"), isIgnored = true),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "User"), modelDecl)
        val param = mockParameter("user", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO users (name) VALUES (:name)")
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── Model property with ClassName adds correct imports (toBindings branch) ──

    @Test
    fun `INSERT with model property of ClassName type adds import`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val modelDecl = mockModelClassDecl("com.example.MyModel", listOf(
            ModelProperty("name", ClassName("kotlin", "String")),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "MyModel"), modelDecl)
        val param = mockParameter("model", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (name) VALUES (:name)")
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── SELECT returning model with Long, Float, Double, Boolean properties (map mappers) ──

    @Test
    fun `SELECT returning model with Long property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("count", ClassName("kotlin", "Long")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT count FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    @Test
    fun `SELECT returning model with Float property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("score", ClassName("kotlin", "Float")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT score FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    @Test
    fun `SELECT returning model with Double property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("price", ClassName("kotlin", "Double")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT price FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    @Test
    fun `SELECT returning model with Boolean property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("active", ClassName("kotlin", "Boolean")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT active FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    @Test
    fun `SELECT returning model with String property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("name", ClassName("kotlin", "String")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT name FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── isCollection in toConstructor (List return builds results.add) ──

    @Test
    fun `SELECT returning List uses results add in constructor`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val innerDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
        ))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("com.example", "User"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM users")
        val fn = mockFunction("findAll", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should use results.add", generatedCode().contains("results.add"))
    }

    // ── Unsupported single parameter type error ──

    @Test
    fun `errors on unsupported single parameter type`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (v) VALUES (:p)")
        // Create a non-KSClassDeclaration parameter type so it hits the else error branch
        val nonClassDecl = mockNonClassDecl(ClassName("com.example", "Unknown"))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "Unknown"), nonClassDecl)
        val param = mockParameter("p", paramTypeRef)
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Unsupported parameter type"))
        }
    }

    // ── Multi-param with serializable param should pass ──

    @Test
    fun `multi-param with serializable class param does not error`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (a, b) VALUES (:a, :b)")
        // Create a serializable class parameter
        val serDecl = mockSimpleClassDecl(ClassName("com.example", "Payload"))
        val serAnnotation = mockk<KSAnnotation>()
        every { serAnnotation.shortName } returns mockKSName("Serializable")
        every { serDecl.annotations } returns sequenceOf(serAnnotation)
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Payload" }) } returns serDecl

        val p1 = mockParameter("a", mockTypeRef(ClassName("com.example", "Payload"), serDecl))
        val p2 = mockParameter("b", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(p1, p2), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should use serializable bind", generatedCode().contains("serializable"))
    }

    // ── Multi-param with enum param should pass ──

    @Test
    fun `multi-param with enum class param does not error`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (a, b) VALUES (:a, :b)")
        val enumDecl = mockSimpleClassDecl(ClassName("com.example", "Status"), ClassKind.ENUM_CLASS)
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Status" }) } returns enumDecl

        val p1 = mockParameter("a", mockTypeRef(ClassName("com.example", "Status"), enumDecl))
        val p2 = mockParameter("b", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(p1, p2), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should use enum bind", generatedCode().contains("enum"))
    }

    // ── Unsupported bind mapper type error ──

    @Test
    fun `errors on unsupported bind mapper type`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        // Create model with a type that doesn't match any bind mapper and isn't enum or serializable
        val unknownDecl = mockSimpleClassDecl(ClassName("com.example", "Unknown"))
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Unknown" }) } returns unknownDecl
        val modelDecl = mockModelClassDecl("com.example.Model", listOf(
            ModelProperty("p", ClassName("com.example", "Unknown")),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "Model"), modelDecl)
        val param = mockParameter("model", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (p) VALUES (:p)")
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unsupported type"))
        }
    }

    // ── Unsupported map mapper type error ──

    @Test
    fun `errors on unsupported map mapper type in constructor`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val unknownDecl = mockSimpleClassDecl(ClassName("com.example", "Unknown"))
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Unknown" }) } returns unknownDecl
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("p", ClassName("com.example", "Unknown")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT p FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unsupported type"))
        }
    }

    // ── Nullable DbMapper property in toConstructor ──

    @Test
    fun `SELECT returning model with nullable DbMapper property`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("custom", ClassName("com.example", "CustomType").copy(nullable = true), nullable = true, hasDbMapper = true, dbMapperQualifiedName = "com.example.CustomMapper"),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT custom FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Should use CustomMapper", code.contains("CustomMapper"))
        // Nullable dbMapper property should NOT have error check
        assertTrue("Nullable should not error", !code.contains("custom is required"))
    }

    // ── TypeVariableName in defaultBindMappers ──

    @Test
    fun `bind TypeVariableName param resolves via resolver`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        // Create model with TypeVariableName property to exercise the TypeVariableName branch
        val tvn = TypeVariableName("T")
        val modelDecl = mockModelClassDecl("com.example.Generic", listOf(
            ModelProperty("p", tvn),
        ))
        // Set up resolver to return an enum for "T" so the bind mapper can handle it
        val enumDecl = mockSimpleClassDecl(ClassName("com.example", "T"), ClassKind.ENUM_CLASS)
        every { resolver.getClassDeclarationByName(match { it.asString() == "T" }) } returns enumDecl
        val paramTypeRef = mockTypeRef(ClassName("com.example", "Generic"), modelDecl)
        val param = mockParameter("model", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (p) VALUES (:p)")
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should use enum bind for T", generatedCode().contains("enum"))
    }

    // ── TypeVariableName in defaultMapMappers ──

    @Test
    fun `map TypeVariableName property resolves via resolver`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val tvn = TypeVariableName("T")
        val enumDecl = mockSimpleClassDecl(ClassName("com.example", "T"), ClassKind.ENUM_CLASS)
        every { resolver.getClassDeclarationByName(match { it.asString() == "T" }) } returns enumDecl
        val modelDecl = mockModelClassDecl("com.example.Generic", listOf(
            ModelProperty("p", tvn),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Generic"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT p FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should use enum map for T", generatedCode().contains("enum"))
    }

    // ── Missing model column error ──

    @Test
    fun `errors on missing model column for query parameter`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        // Model has "name" property, but query references "missing_col"
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("name", ClassName("kotlin", "String")),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "User"), modelDecl)
        val param = mockParameter("user", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO users (missing_col) VALUES (:missing_col)")
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("missing model column"))
        }
    }

    // ── ParameterizedTypeName in model bindings import path ──

    @Test
    fun `INSERT with model having ParameterizedTypeName property adds imports`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
        val modelDecl = mockModelClassDecl("com.example.MyModel", listOf(
            ModelProperty("tags", listType),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "MyModel"), modelDecl)
        val param = mockParameter("model", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (tags) VALUES (:tags)")
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── No-model multi-param default bind path ──

    @Test
    fun `INSERT with multiple primitive params uses no-model bind path`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (a, b, c) VALUES (:a, :b, :c)")
        val p1 = mockParameter("a", mockTypeRef(ClassName("kotlin", "String")))
        val p2 = mockParameter("b", mockTypeRef(ClassName("kotlin", "Int")))
        val p3 = mockParameter("c", mockTypeRef(ClassName("kotlin", "Boolean")))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(p1, p2, p3), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── UPDATE returning model with primary constructor (non-isPrimitive, model, non-nullable) ──

    @Test
    fun `UPDATE returning non-nullable model enters constructor path`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "User"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO users (id) VALUES (:id) RETURNING *")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("insertReturning", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Should construct User", code.contains("User"))
        assertTrue("INSERT should mark commit", code.contains("markNeedsCommitOrRollback"))
    }

    // ── Single param isPrimitive goes to no-model bind path ──

    @Test
    fun `single isPrimitive param enters no-model bind path`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (v) VALUES (:v)")
        val param = mockParameter("v", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should bind String", generatedCode().contains("string.bind"))
    }

    // ── Single enum param goes to no-model bind path ──

    @Test
    fun `single enum param enters no-model bind path`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (v) VALUES (:v)")
        val enumDecl = mockSimpleClassDecl(ClassName("com.example", "Status"), ClassKind.ENUM_CLASS)
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Status" }) } returns enumDecl
        val param = mockParameter("v", mockTypeRef(ClassName("com.example", "Status"), enumDecl))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should use enum bind", generatedCode().contains("enum"))
    }

    // ── Single collection param goes to no-model bind path ──

    @Test
    fun `single collection param enters no-model bind path`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (v) VALUES (:v)")
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
        val param = mockParameter("v", mockTypeRef(listType))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── SELECT returning Set of models with collection constructor path ──

    @Test
    fun `SELECT returning Set uses mutableSetOf in constructor`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val innerDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
        ))
        every { innerDecl.simpleName } returns mockKSName("Set")
        val setType = ClassName("kotlin.collections", "Set").parameterizedBy(ClassName("com.example", "User"))
        val returnTypeRef = mockCollectionReturnTypeRef(setType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM users")
        val fn = mockFunction("findAll", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Should use mutableSetOf", code.contains("mutableSetOf"))
        assertTrue("Should use results.add", code.contains("results.add"))
    }

    // ── WildcardTypeName hits unsupported type in defaultBindMappers (line 159) ──

    @Test
    fun `errors on unsupported WildcardTypeName in bind mappers`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        // Use STAR (WildcardTypeName) which is not ClassName, ParameterizedTypeName, or TypeVariableName
        val modelDecl = mockModelClassDecl("com.example.WildModel", listOf(
            ModelProperty("p", STAR),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "WildModel"), modelDecl)
        val param = mockParameter("model", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (p) VALUES (:p)")
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unsupported type"))
        }
    }

    // ── WildcardTypeName hits unsupported type in defaultMapMappers (line 207) ──

    @Test
    fun `errors on unsupported WildcardTypeName in map mappers`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.WildModel", listOf(
            ModelProperty("p", STAR),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "WildModel"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT p FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unsupported type"))
        }
    }

    // ── containingFile null error ──

    @Test
    fun `errors when containingFile is null`() {
        prepareGenerator()
        val classDecl = mockk<KSClassDeclaration>()
        every { classDecl.containingFile } returns null
        every { classDecl.getAllFunctions() } returns emptySequence()
        val repo = FoundRepository(
            repository = ClassName("com.example", "TestRepo"),
            provider = ClassName("com.example", "TestRepoProvider"),
            providers = ClassName("com.example", "TestRepoImpl"),
            classDeclaration = classDecl
        )
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("No containing file"))
        }
    }

    // ── ParameterizedTypeName return type with non-ClassName type argument ──

    @Test
    fun `generate with ParameterizedTypeName return with non-ClassName type arg`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val innerDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
        ))
        // Use STAR as a type argument instead of ClassName to test the non-ClassName branch
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(STAR)
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM users")
        val fn = mockFunction("findAll", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── No-model DbMapper null annotation path (no DbMapper on declaration) ──

    @Test
    fun `INSERT with multiple params and no DbMapper uses default bind`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (a, b) VALUES (:a, :b)")
        // Both params are primitive, no DbMapper annotations
        val p1 = mockParameter("a", mockTypeRef(ClassName("kotlin", "String")))
        val p2 = mockParameter("b", mockTypeRef(ClassName("kotlin", "Long")))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(p1, p2), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Should use string bind", code.contains("string.bind"))
        assertTrue("Should use long bind", code.contains("long.bind"))
    }

    // ── SELECT returning non-nullable non-model non-collection primitive ──

    @Test
    fun `SELECT returning non-nullable non-collection primitive has error path`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Int"))
        val queryAnnotation = mockQueryAnnotation("SELECT count(*) FROM items")
        val fn = mockFunction("count", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Non-nullable should have error path", code.contains("error"))
    }

    // ── SELECT returning nullable non-model non-collection primitive ──

    @Test
    fun `SELECT returning nullable non-collection primitive returns null`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Int").copy(nullable = true), isNullable = true)
        val queryAnnotation = mockQueryAnnotation("SELECT count(*) FROM items")
        val fn = mockFunction("count", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Nullable should return null", generatedCode().contains("null"))
    }

    // ── UPDATE returning nullable isPrimitive type (covers nullable toUpdate primitive branch) ──

    @Test
    fun `UPDATE returning nullable isPrimitive type`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Int").copy(nullable = true), isNullable = true)
        val queryAnnotation = mockQueryAnnotation("UPDATE t SET v = :v RETURNING count(*)")
        val param = mockParameter("v", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("update", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Nullable update should not error on null", !code.contains("row value is null"))
    }

    // ── SELECT returning nullable isPrimitive model type (covers toQuery isPrimitive nullable branches) ──

    @Test
    fun `SELECT returning nullable isPrimitive model`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("bosca.serialization.UUID", listOf(
            ModelProperty("value", ClassName("kotlin", "String")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("bosca.serialization", "UUID").copy(nullable = true), isNullable = true, classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM users WHERE name = :name")
        val param = mockParameter("name", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("findId", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Nullable isPrimitive model should have null", code.contains("null"))
    }

    // ── Bind type where resolver returns null for class name (null declaration path in defaultBindMappers) ──

    @Test
    fun `errors on bind type when resolver returns null declaration`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Missing" }) } returns null
        val modelDecl = mockModelClassDecl("com.example.Model", listOf(
            ModelProperty("p", ClassName("com.example", "Missing")),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "Model"), modelDecl)
        val param = mockParameter("model", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (p) VALUES (:p)")
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unsupported type"))
        }
    }

    // ── Map type where resolver returns null for class name (null declaration path in defaultMapMappers) ──

    @Test
    fun `errors on map type when resolver returns null declaration`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Missing" }) } returns null
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("p", ClassName("com.example", "Missing")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT p FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unsupported type"))
        }
    }

    // ── Multi-param with non-KSClassDeclaration param passes multi-param check ──

    @Test
    fun `multi-param with non-KSClassDeclaration param does not error`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (a, b) VALUES (:a, :b)")
        // Use a non-KSClassDeclaration for one param (the multi-param check only flags KSClassDeclaration)
        val nonClassDecl = mockNonClassDecl(ClassName("com.example", "Custom"))
        // Set up resolver to return null for this type so defaultBindMappers hits the error path
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Custom" }) } returns null
        val p1 = mockParameter("a", mockTypeRef(ClassName("com.example", "Custom"), nonClassDecl))
        val p2 = mockParameter("b", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(p1, p2), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        // This should not error in multi-param check because non-KSClassDeclaration params are not flagged
        // It will fail in defaultBindMappers because the type is not recognized
        try {
            generator.generate(listOf(repo))
        } catch (e: IllegalStateException) {
            // Expected to fail in bind mapper with "unsupported type", NOT "multiple model parameters"
            assertTrue("Should fail in bind mapper, not multi-param check", e.message!!.contains("unsupported type"))
        }
    }

    // ── UPDATE with non-primitive non-KSClassDeclaration return (covers toUpdate type check branch) ──

    @Test
    fun `UPDATE returning non-primitive non-class errors on unsupported type`() {
        val resolver = mockResolver()
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.custom.Result" }) } returns null
        prepareGenerator(resolver)
        // Use a non-KSClassDeclaration return type that is NOT isPrimitive
        val nonClassDecl = mockNonClassDecl(ClassName("com.custom", "Result"))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.custom", "Result"), classDecl = null)
        val queryAnnotation = mockQueryAnnotation("UPDATE t SET v = :v RETURNING result")
        val param = mockParameter("v", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("update", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw for unsupported type")
        } catch (e: IllegalStateException) {
            assertTrue("Should report unsupported type", e.message!!.contains("unsupported type"))
        }
    }

    // ── SELECT returning nullable non-model collection of primitives ──

    @Test
    fun `SELECT returning non-model collection with nullable inner type`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val innerDecl = mockNonClassDecl(ClassName("kotlin", "String"))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String").copy(nullable = true))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT name FROM users")
        val fn = mockFunction("findAllNames", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── DELETE returning non-Unit type ──

    @Test
    fun `DELETE returning Int`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Int"))
        val queryAnnotation = mockQueryAnnotation("DELETE FROM t WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("delete", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("DELETE should mark commit", generatedCode().contains("markNeedsCommitOrRollback"))
    }

    // ── SELECT returning model without DbConstructor falls to primaryConstructor ──

    @Test
    fun `SELECT returning model without DbConstructor uses primary constructor`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        // Create model where getAllFunctions returns functions without DbConstructor annotation
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
        ))
        // Ensure no functions have DbConstructor
        val nonAnnotatedFn = mockk<KSFunctionDeclaration>()
        every { nonAnnotatedFn.annotations } returns emptySequence()
        every { modelDecl.getAllFunctions() } returns sequenceOf(nonAnnotatedFn)

        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "User"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM users WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── UPDATE returning model without DbConstructor uses primary constructor ──

    @Test
    fun `UPDATE returning model without DbConstructor uses primary constructor`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
        ))
        // Ensure no functions have DbConstructor
        val nonAnnotatedFn = mockk<KSFunctionDeclaration>()
        every { nonAnnotatedFn.annotations } returns emptySequence()
        every { modelDecl.getAllFunctions() } returns sequenceOf(nonAnnotatedFn)

        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "User"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO users (id) VALUES (:id) RETURNING *")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("insertReturning", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── SELECT returning non-nullable model with non-nullable DbMapper property (non-nullable check) ──

    @Test
    fun `SELECT returning model with non-nullable DbMapper property has error check`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("custom", ClassName("com.example", "CustomType"), nullable = false, hasDbMapper = true, dbMapperQualifiedName = "com.example.CustomMapper"),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT custom FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Non-nullable should have error check", code.contains("custom is required"))
    }

    // ── Missing function parameter for query parameter in no-model path (line 288 error) ──

    @Test
    fun `errors when query param not in function params for no-model path`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        // Query references :missing but function only has :existing
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (a, b) VALUES (:existing, :missing)")
        val p = mockParameter("existing", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(p), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("missing parameter type for parameter"))
        }
    }

    // ── SELECT returning nullable non-model collection (line 409 nullable branch) ──

    @Test
    fun `SELECT returning nullable non-model collection`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val innerDecl = mockNonClassDecl(ClassName("kotlin", "String"))
        val innerNullableType = ClassName("kotlin", "String").copy(nullable = true)
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(innerNullableType)
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT name FROM users")
        val fn = mockFunction("findAllNames", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── UPDATE returning non-Unit with a non-nullable non-primitive return using error on null ──

    @Test
    fun `UPDATE returning non-nullable non-primitive non-model errors on unsupported type`() {
        val resolver = mockResolver()
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.custom.Result" }) } returns null
        prepareGenerator(resolver)
        val nonClassDecl = mockNonClassDecl(ClassName("com.custom", "Result"))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.custom", "Result"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (v) VALUES (:v) RETURNING result")
        val param = mockParameter("v", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw for unsupported type")
        } catch (e: IllegalStateException) {
            assertTrue("Should report unsupported type", e.message!!.contains("unsupported type"))
        }
    }

    // ── INSERT returning Unit should have markNeedsCommitOrRollback in Unit path (line 340) ──

    @Test
    fun `INSERT returning Unit marks commit in Unit path`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (v) VALUES (:v)")
        val param = mockParameter("v", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        val code = generatedCode()
        assertTrue("INSERT Unit path should have markNeedsCommitOrRollback", code.contains("markNeedsCommitOrRollback"))
        assertTrue("Should use stmt.execute", code.contains("stmt.execute()"))
    }

    // ── Null parameter name error branches ──

    @Test
    fun `errors on null parameter name in toParameters`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (a) VALUES (:a)")
        val p1 = mockParameter("a", mockTypeRef(ClassName("kotlin", "String")))
        // p2 has null name — will be skipped during binding but caught in toParameters
        val p2 = mockk<KSValueParameter>()
        every { p2.name } returns null
        every { p2.type } returns mockTypeRef(ClassName("kotlin", "Int"))
        // Put null-name param first so it's encountered in binding loop too (line 288 branch)
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(p2, p1), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("missing parameter name"))
        }
    }

    @Test
    fun `errors on null parameter name in single model param path`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (name) VALUES (:name)")
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("name", ClassName("kotlin", "String")),
        ))
        val param = mockk<KSValueParameter>()
        every { param.name } returns null
        val paramTypeRef = mockTypeRef(ClassName("com.example", "User"), modelDecl)
        every { param.type } returns paramTypeRef
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("missing name"))
        }
    }

    // ── Null returnType error branches ──

    @Test
    fun `errors on null returnType in toUpdate`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (v) VALUES (:v)")
        val param = mockParameter("v", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param))
        every { fn.returnType } returns null
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("missing return type"))
        }
    }

    @Test
    fun `errors on null returnType in toQuery`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val queryAnnotation = mockQueryAnnotation("SELECT v FROM t WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("find", listOf(queryAnnotation), listOf(param))
        every { fn.returnType } returns null
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("missing return type"))
        }
    }

    // ── Null constructor parameter name in toConstructor ──

    @Test
    fun `errors on null constructor parameter name in toConstructor`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        // Build a model with a constructor param that has null name
        val modelDecl = mockk<KSClassDeclaration>(relaxed = true)
        every { modelDecl.classKind } returns ClassKind.CLASS
        every { modelDecl.simpleName } returns mockKSName("Item")
        every { modelDecl.qualifiedName } returns mockKSName("com.example.Item")
        every { modelDecl.packageName } returns mockKSName("com.example")
        every { modelDecl.annotations } returns emptySequence()

        // Create a property so DatabaseModel has it
        val prop = mockk<KSPropertyDeclaration>()
        every { prop.simpleName } returns mockKSName("id")
        every { prop.annotations } returns emptySequence()
        val propTypeRef = mockk<KSTypeReference>()
        val propKsType = mockk<KSType>()
        every { propTypeRef.resolve() } returns propKsType
        every { propTypeRef.toTypeName(any()) } returns ClassName("kotlin", "Int")
        every { propTypeRef.toTypeName() } returns ClassName("kotlin", "Int")
        every { propKsType.isMarkedNullable } returns false
        every { propKsType.toTypeName(any()) } returns ClassName("kotlin", "Int")
        every { propKsType.toTypeName() } returns ClassName("kotlin", "Int")
        every { propKsType.arguments } returns emptyList()
        val propTypeDecl = mockk<KSClassDeclaration>()
        every { propTypeDecl.annotations } returns emptySequence()
        every { propKsType.declaration } returns propTypeDecl
        every { prop.type } returns propTypeRef
        every { modelDecl.getAllProperties() } returns sequenceOf(prop)

        // Constructor with a null-name parameter
        val cp = mockk<KSValueParameter>()
        every { cp.name } returns null
        val cpTypeRef = mockk<KSTypeReference>()
        val cpKsType = mockk<KSType>()
        every { cpTypeRef.resolve() } returns cpKsType
        every { cpTypeRef.toTypeName(any()) } returns ClassName("kotlin", "Int")
        every { cpTypeRef.toTypeName() } returns ClassName("kotlin", "Int")
        every { cpKsType.toTypeName(any()) } returns ClassName("kotlin", "Int")
        every { cpKsType.toTypeName() } returns ClassName("kotlin", "Int")
        every { cpKsType.arguments } returns emptyList()
        every { cpKsType.declaration } returns propTypeDecl
        every { cp.type } returns cpTypeRef

        val constructor = mockk<KSFunctionDeclaration>()
        every { constructor.parameters } returns listOf(cp)
        every { constructor.simpleName } returns mockKSName("<init>")
        every { constructor.parentDeclaration } returns modelDecl
        every { constructor.annotations } returns emptySequence()
        every { modelDecl.primaryConstructor } returns constructor
        every { modelDecl.getAllFunctions() } returns sequenceOf(constructor)

        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("missing parameter name"))
        }
    }

    // ── Error in defaultMapMappers with null parentDeclaration ──

    @Test
    fun `error message in defaultMapMappers includes null parentDeclaration`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Unknown" }) } returns null
        // Build model with custom constructor that has null parentDeclaration
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("p", ClassName("com.example", "Unknown")),
        ))
        val constructor = modelDecl.getAllFunctions().first()
        every { constructor.parentDeclaration } returns null
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT p FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unsupported type"))
        }
    }

    // ── SELECT returning model with List property in collection (tests results.add toList in constructor) ──

    @Test
    fun `SELECT returning List of models with List property`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val listPropType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
        val innerDecl = mockModelClassDecl("com.example.Tagged", listOf(
            ModelProperty("tags", listPropType),
        ))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("com.example", "Tagged"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT tags FROM items")
        val fn = mockFunction("findAll", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Should use toList for List map", code.contains("toList"))
        assertTrue("Should use results.add", code.contains("results.add"))
    }

    // ── TypeVariableName return type skips import (line 134 else branch) ──

    @Test
    fun `SELECT with TypeVariableName return type skips imports`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val tvn = TypeVariableName("T")
        // Use a non-KSClassDeclaration so toQuery enters non-model path
        val nonClassDecl = mockNonClassDecl(ClassName("kotlin", "T"))
        val returnTypeRef = mockTypeRef(tvn, nonClassDecl)
        // Set up resolver to handle "T" as an enum in defaultMapMappers
        val enumDecl = mockSimpleClassDecl(ClassName("com.example", "T"), ClassKind.ENUM_CLASS)
        every { resolver.getClassDeclarationByName(match { it.asString() == "T" }) } returns enumDecl
        val queryAnnotation = mockQueryAnnotation("SELECT v FROM t WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("find", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }

    // ── No constructor found error in toQuery ──

    @Test
    fun `errors when model has no constructor in toQuery`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.NoCtorModel", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
        ))
        // Remove both DbConstructor and primary constructor
        every { modelDecl.getAllFunctions() } returns emptySequence()
        every { modelDecl.primaryConstructor } returns null
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "NoCtorModel"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("No constructor found"))
        }
    }

    // ── No constructor found error in toUpdate ──

    @Test
    fun `errors when model has no constructor in toUpdate`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.NoCtorModel", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
        ))
        every { modelDecl.getAllFunctions() } returns emptySequence()
        every { modelDecl.primaryConstructor } returns null
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "NoCtorModel"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO items (id) VALUES (:id) RETURNING *")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("No constructor found"))
        }
    }

    // ── UPDATE returning nullable non-primitive non-model (covers nullable branch in toUpdate line 323) ──

    @Test
    fun `UPDATE returning nullable non-primitive non-model errors on unsupported type`() {
        val resolver = mockResolver()
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.custom.Result" }) } returns null
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("com.custom", "Result").copy(nullable = true), isNullable = true)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (v) VALUES (:v) RETURNING result")
        val param = mockParameter("v", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw for unsupported type")
        } catch (e: IllegalStateException) {
            assertTrue("Should report unsupported type", e.message!!.contains("unsupported type"))
        }
    }

    // ── UPDATE returning ParameterizedTypeName (covers line 329 non-ClassName branch) ──

    @Test
    fun `UPDATE returning ParameterizedTypeName enters non-Unit path`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val innerDecl = mockNonClassDecl(ClassName("kotlin", "String"))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (v) VALUES (:v) RETURNING *")
        val param = mockParameter("v", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        val code = generatedCode()
        assertTrue("Should mark commit for INSERT", code.contains("markNeedsCommitOrRollback"))
    }

    // ── Error in defaultMapMappers with null qualifiedName on parentDeclaration ──

    @Test
    fun `error message in defaultMapMappers handles null qualifiedName`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Unknown" }) } returns null
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("p", ClassName("com.example", "Unknown")),
        ))
        val constructor = modelDecl.getAllFunctions().first()
        // parentDeclaration is non-null but qualifiedName returns null
        val parentDecl = mockk<KSClassDeclaration>(relaxed = true)
        every { parentDecl.qualifiedName } returns null
        every { constructor.parentDeclaration } returns parentDecl
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT p FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unsupported type"))
        }
    }

    // ── DbMapper annotation with null value falls back to default (toBindings no-model path, line 296) ──

    @Test
    fun `no-model param with DbMapper annotation having null value uses default bind`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (a) VALUES (:a)")

        // Create annotation where value is null
        val dbMapperAnnotation = mockk<KSAnnotation>()
        every { dbMapperAnnotation.shortName } returns mockKSName("DbMapper")
        val dbMapperArg = mockk<KSValueArgument>()
        every { dbMapperArg.value } returns null
        every { dbMapperAnnotation.arguments } returns listOf(dbMapperArg)

        val paramDecl = mockSimpleClassDecl(ClassName("kotlin", "String"))
        every { paramDecl.annotations } returns sequenceOf(dbMapperAnnotation)
        val param = mockParameter("a", mockTypeRef(ClassName("kotlin", "String"), paramDecl))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should use default string bind", generatedCode().contains("string.bind"))
    }

    // ── DbMapper annotation where qualifiedName is null → error (toBindings no-model path, line 296) ──

    @Test
    fun `no-model param with DbMapper annotation having null qualifiedName errors`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (a) VALUES (:a)")

        val dbMapperType = mockk<KSType>()
        val dbMapperDecl = mockk<KSClassDeclaration>()
        every { dbMapperType.declaration } returns dbMapperDecl
        every { dbMapperDecl.qualifiedName } returns null
        val dbMapperAnnotation = mockk<KSAnnotation>()
        every { dbMapperAnnotation.shortName } returns mockKSName("DbMapper")
        val dbMapperArg = mockk<KSValueArgument>()
        every { dbMapperArg.value } returns dbMapperType
        every { dbMapperAnnotation.arguments } returns listOf(dbMapperArg)

        val paramDecl = mockSimpleClassDecl(ClassName("kotlin", "String"))
        every { paramDecl.annotations } returns sequenceOf(dbMapperAnnotation)
        val param = mockParameter("a", mockTypeRef(ClassName("kotlin", "String"), paramDecl))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("DbMapper annotation value is not a class"))
        }
    }

    // ── DbMapper with null value in toConstructor (line 519) ──

    @Test
    fun `constructor param with DbMapper annotation having null value uses default map`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("name", ClassName("kotlin", "String")),
        ))
        // Override the constructor param type's annotation to have @DbMapper with null value
        val constructor = modelDecl.getAllFunctions().first()
        val cpType = constructor.parameters.first().type.resolve()
        val dbMapperAnnotation = mockk<KSAnnotation>()
        every { dbMapperAnnotation.shortName } returns mockKSName("DbMapper")
        val dbMapperArg = mockk<KSValueArgument>()
        every { dbMapperArg.value } returns null
        every { dbMapperAnnotation.arguments } returns listOf(dbMapperArg)
        every { cpType.declaration.annotations } returns sequenceOf(dbMapperAnnotation)

        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT name FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
        assertTrue("Should use default string map", generatedCode().contains("string.map"))
    }

    // ── DbMapper with null qualifiedName in toConstructor (line 519) ──

    @Test
    fun `constructor param with DbMapper annotation having null qualifiedName errors`() {
        val resolver = mockResolver()
        prepareGenerator(resolver)
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("name", ClassName("kotlin", "String")),
        ))
        val constructor = modelDecl.getAllFunctions().first()
        val cpType = constructor.parameters.first().type.resolve()
        val dbMapperType = mockk<KSType>()
        val dbMapperDecl = mockk<KSClassDeclaration>()
        every { dbMapperType.declaration } returns dbMapperDecl
        every { dbMapperDecl.qualifiedName } returns null
        val dbMapperAnnotation = mockk<KSAnnotation>()
        every { dbMapperAnnotation.shortName } returns mockKSName("DbMapper")
        val dbMapperArg = mockk<KSValueArgument>()
        every { dbMapperArg.value } returns dbMapperType
        every { dbMapperAnnotation.arguments } returns listOf(dbMapperArg)
        every { cpType.declaration.annotations } returns sequenceOf(dbMapperAnnotation)

        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT name FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        try {
            generator.generate(listOf(repo))
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("DbMapper annotation value is not a class"))
        }
    }

    // ── SELECT returning nullable collection of primitives (covers isNullable on collection return) ──

    @Test
    fun `SELECT returning nullable List of primitives`() {
        val resolver = mockResolver(collectionAssignable = true)
        prepareGenerator(resolver)
        val innerDecl = mockNonClassDecl(ClassName("kotlin", "String"))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
        val nullableListType = listType.copy(nullable = true)
        val outerDecl = mockk<KSClassDeclaration>(relaxed = true)
        every { outerDecl.annotations } returns emptySequence()
        every { outerDecl.packageName } returns mockKSName("kotlin.collections")

        val innerKsType = mockk<KSType>()
        val innerTypeRef = mockk<KSTypeReference>()
        every { innerTypeRef.resolve() } returns innerKsType
        every { innerKsType.declaration } returns innerDecl
        val typeArg = mockk<KSTypeArgument>()
        every { typeArg.type } returns innerTypeRef

        val returnKsType = mockk<KSType>()
        val returnTypeRef = mockk<KSTypeReference>()
        every { returnTypeRef.resolve() } returns returnKsType
        every { returnTypeRef.toTypeName(any()) } returns nullableListType
        every { returnTypeRef.toTypeName() } returns nullableListType
        every { returnKsType.toTypeName(any()) } returns nullableListType
        every { returnKsType.toTypeName() } returns nullableListType
        every { returnKsType.isMarkedNullable } returns true
        every { returnKsType.declaration } returns outerDecl
        every { returnKsType.arguments } returns listOf(typeArg)

        val queryAnnotation = mockQueryAnnotation("SELECT name FROM users")
        val fn = mockFunction("findAllNames", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertTrue("Expected generated output", outputStream.size() > 0)
    }
}
