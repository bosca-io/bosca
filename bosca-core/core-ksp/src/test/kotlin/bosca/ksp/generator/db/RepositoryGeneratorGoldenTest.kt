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
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
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
 * Golden file tests for [RepositoryGenerator] that verify the COMPLETE generated output
 * for representative scenarios. Each test generates code and compares it against an
 * expected golden file stored in test resources.
 *
 * To update golden files after intentional generator changes, set the environment variable
 * `UPDATE_GOLDEN=true` and run the tests.
 */
class RepositoryGeneratorGoldenTest {

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

    private fun mockNonClassDecl(typeName: TypeName): KSDeclaration {
        val decl = mockk<KSDeclaration>(relaxed = true)
        if (typeName is ClassName) {
            every { decl.simpleName } returns mockKSName(typeName.simpleName)
            every { decl.packageName } returns mockKSName(typeName.packageName)
            every { decl.qualifiedName } returns mockKSName(typeName.canonicalName)
        }
        return decl
    }

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

        val props = properties.filter { !it.isIgnored }.map { mp ->
            val prop = mockk<KSPropertyDeclaration>()
            every { prop.simpleName } returns mockKSName(mp.name)
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
            val propTypeRef = mockk<KSTypeReference>()
            val propKsType = mockk<KSType>()
            every { propTypeRef.resolve() } returns propKsType
            every { propTypeRef.toTypeName(any()) } returns mp.typeName
            every { propTypeRef.toTypeName() } returns mp.typeName
            every { propKsType.isMarkedNullable } returns mp.nullable
            every { propKsType.toTypeName(any()) } returns mp.typeName
            every { propKsType.toTypeName() } returns mp.typeName
            every { propKsType.arguments } returns emptyList()
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

        val ignoredProps = properties.filter { it.isIgnored }.map { mp ->
            val prop = mockk<KSPropertyDeclaration>()
            every { prop.simpleName } returns mockKSName(mp.name)
            val ignoreAnnotation = mockk<KSAnnotation>()
            every { ignoreAnnotation.shortName } returns mockKSName("Ignore")
            every { prop.annotations } returns sequenceOf(ignoreAnnotation)
            prop
        }

        every { decl.getAllProperties() } returns (props + ignoredProps).asSequence()

        val constructorParams = properties.filter { !it.isIgnored }.map { mp ->
            val cp = mockk<KSValueParameter>()
            every { cp.name } returns mockKSName(mp.name)
            val cpTypeRef = mockk<KSTypeReference>()
            val cpKsType = mockk<KSType>()
            every { cpTypeRef.resolve() } returns cpKsType
            every { cpTypeRef.toTypeName(any()) } returns mp.typeName
            every { cpTypeRef.toTypeName() } returns mp.typeName
            every { cpKsType.toTypeName(any()) } returns mp.typeName
            every { cpKsType.toTypeName() } returns mp.typeName
            every { cpKsType.arguments } returns emptyList()
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

    private fun mockReturnTypeRef(
        typeName: TypeName,
        isNullable: Boolean = false,
        classDecl: KSClassDeclaration? = null
    ): KSTypeReference {
        val decl: KSDeclaration = classDecl ?: mockNonClassDecl(typeName)
        return mockTypeRef(typeName, decl, isNullable)
    }

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

    private fun generatedCode(): String = outputStream.toString(Charsets.UTF_8.name())

    // ── Golden File Assertion ──

    /**
     * Asserts that the generated code matches the golden file stored at
     * `src/test/resources/golden/repository/{goldenName}.txt`.
     *
     * If the golden file does not exist, it is created from the current output
     * and the test fails with instructions to review and re-run.
     *
     * Set `UPDATE_GOLDEN=true` environment variable to overwrite existing golden files.
     */
    private fun assertMatchesGolden(goldenName: String) {
        val code = generatedCode()
        val resourcePath = "golden/repository/$goldenName.txt"
        val resourceDir = File("src/test/resources/golden/repository")
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
    fun `golden - SELECT returning primitive with single param`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "String"))
        val queryAnnotation = mockQueryAnnotation("SELECT name FROM t WHERE id = :id")
        val paramTypeRef = mockTypeRef(ClassName("kotlin", "Int"))
        val param = mockParameter("id", paramTypeRef)
        val fn = mockFunction("findNameById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("select-primitive-param")
    }

    @Test
    fun `golden - SELECT returning nullable primitive`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
        val typeName = ClassName("kotlin", "String").copy(nullable = true)
        val returnTypeRef = mockReturnTypeRef(typeName, isNullable = true)
        val queryAnnotation = mockQueryAnnotation("SELECT name FROM t WHERE id = :id")
        val paramTypeRef = mockTypeRef(ClassName("kotlin", "Int"))
        val param = mockParameter("id", paramTypeRef)
        val fn = mockFunction("findNameById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("select-nullable-primitive")
    }

    @Test
    fun `golden - INSERT returning Unit with model parameter`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
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
        assertMatchesGolden("insert-unit-model")
    }

    @Test
    fun `golden - DELETE returning Unit with single param`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("DELETE FROM users WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("deleteById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("delete-unit-param")
    }

    @Test
    fun `golden - SELECT returning model with primary constructor`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
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
        assertMatchesGolden("select-model")
    }

    @Test
    fun `golden - SELECT returning nullable model`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty("name", ClassName("kotlin", "String")),
        ))
        val returnTypeRef = mockReturnTypeRef(
            ClassName("com.example", "User").copy(nullable = true),
            isNullable = true,
            classDecl = modelDecl
        )
        val queryAnnotation = mockQueryAnnotation("SELECT id, name FROM users WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("select-nullable-model")
    }

    @Test
    fun `golden - SELECT returning model with DbConstructor`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
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
        assertMatchesGolden("select-model-dbconstructor")
    }

    @Test
    fun `golden - SELECT returning List of models`() {
        val resolver = mockResolver(collectionAssignable = true)
        generator.prepare(resolver)
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
        assertMatchesGolden("select-list-models")
    }

    @Test
    fun `golden - SELECT returning Set of models`() {
        val resolver = mockResolver(collectionAssignable = true)
        generator.prepare(resolver)
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
        assertMatchesGolden("select-set-models")
    }

    @Test
    fun `golden - SELECT returning List of primitives`() {
        val resolver = mockResolver(collectionAssignable = true)
        generator.prepare(resolver)
        val innerDecl = mockNonClassDecl(ClassName("kotlin", "String"))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT name FROM users")
        val fn = mockFunction("findAllNames", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("select-list-primitives")
    }

    @Test
    fun `golden - INSERT with model having DbMapper property`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val modelDecl = mockModelClassDecl("com.example.MyModel", listOf(
            ModelProperty("name", ClassName("kotlin", "String")),
            ModelProperty("custom", ClassName("com.example", "CustomType"), hasDbMapper = true, dbMapperQualifiedName = "com.example.CustomMapper"),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "MyModel"), modelDecl)
        val param = mockParameter("model", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (name, custom) VALUES (:name, :custom)")
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("insert-model-dbmapper")
    }

    @Test
    fun `golden - SELECT returning model with DbMapper property`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty("custom", ClassName("com.example", "CustomType"), hasDbMapper = true, dbMapperQualifiedName = "com.example.CustomMapper"),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id, custom FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("select-model-dbmapper")
    }

    @Test
    fun `golden - INSERT with model having ColumnName annotation`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("userName", ClassName("kotlin", "String"), hasColumnName = true, columnName = "user_name"),
            ModelProperty("email", ClassName("kotlin", "String")),
        ))
        val paramTypeRef = mockTypeRef(ClassName("com.example", "User"), modelDecl)
        val param = mockParameter("user", paramTypeRef)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO users (user_name, email) VALUES (:userName, :email)")
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("insert-model-columnname")
    }

    @Test
    fun `golden - INSERT with model having Ignore annotation`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
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
        assertMatchesGolden("insert-model-ignore")
    }

    @Test
    fun `golden - SELECT returning model with multiple property types`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
        val modelDecl = mockModelClassDecl("com.example.Entity", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty("name", ClassName("kotlin", "String")),
            ModelProperty("uuid", ClassName("kotlin.uuid", "Uuid")),
            ModelProperty("created", ClassName("java.time", "OffsetDateTime")),
            ModelProperty("score", ClassName("kotlin", "Double")),
            ModelProperty("active", ClassName("kotlin", "Boolean")),
            ModelProperty("email", ClassName("kotlin", "String").copy(nullable = true), nullable = true),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Entity"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id, name, uuid, created, score, active, email FROM entities WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("select-model-multiple-types")
    }

    @Test
    fun `golden - INSERT RETURNING model`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
        val modelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty("name", ClassName("kotlin", "String")),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "User"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("INSERT INTO users (name) VALUES (:name) RETURNING *")
        val param = mockParameter("name", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("insertReturning", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("insert-returning-model")
    }

    @Test
    fun `golden - DELETE RETURNING nullable scalar`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
        val returnTypeRef = mockReturnTypeRef(
            ClassName("bosca.serialization", "UUID").copy(nullable = true),
            isNullable = true
        )
        val queryAnnotation = mockQueryAnnotation("DELETE FROM tokens WHERE token = :token AND expires > now() RETURNING principal_id")
        val param = mockParameter("token", mockTypeRef(ClassName("kotlin", "String")))
        val fn = mockFunction("consumeToken", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("delete-returning-nullable-scalar")
    }

    @Test
    fun `golden - DELETE RETURNING non-nullable scalar`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Int"))
        val queryAnnotation = mockQueryAnnotation("DELETE FROM items WHERE id = :id RETURNING count")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("deleteReturningCount", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("delete-returning-non-nullable-scalar")
    }

    @Test
    fun `golden - SELECT with no parameters`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Long"))
        val queryAnnotation = mockQueryAnnotation("SELECT count(*) FROM users")
        val fn = mockFunction("count", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("select-no-params")
    }

    @Test
    fun `golden - UPDATE with multiple primitive params`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("UPDATE users SET name = :name, age = :age WHERE id = :id")
        val p1 = mockParameter("name", mockTypeRef(ClassName("kotlin", "String")))
        val p2 = mockParameter("age", mockTypeRef(ClassName("kotlin", "Int")))
        val p3 = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("update", listOf(queryAnnotation), listOf(p1, p2, p3), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("update-multi-param")
    }

    @Test
    fun `golden - multi-function repository`() {
        val resolver = mockResolver(collectionAssignable = true)
        generator.prepare(resolver)

        // Function 1: SELECT returning model
        val userModelDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty("name", ClassName("kotlin", "String")),
        ))
        val selectReturnType = mockReturnTypeRef(
            ClassName("com.example", "User").copy(nullable = true),
            isNullable = true,
            classDecl = userModelDecl
        )
        val selectQuery = mockQueryAnnotation("SELECT id, name FROM users WHERE id = :id")
        val selectParam = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val selectFn = mockFunction("findById", listOf(selectQuery), listOf(selectParam), selectReturnType)

        // Function 2: SELECT returning List
        val listInnerDecl = mockModelClassDecl("com.example.User2", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty("name", ClassName("kotlin", "String")),
        ))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("com.example", "User2"))
        val listReturnType = mockCollectionReturnTypeRef(listType, listInnerDecl)
        val listQuery = mockQueryAnnotation("SELECT id, name FROM users")
        val listFn = mockFunction("findAll", listOf(listQuery), emptyList(), listReturnType)

        // Function 3: INSERT returning Unit
        val insertReturnType = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val insertQuery = mockQueryAnnotation("INSERT INTO users (name) VALUES (:name)")
        val insertParam = mockParameter("name", mockTypeRef(ClassName("kotlin", "String")))
        val insertFn = mockFunction("insert", listOf(insertQuery), listOf(insertParam), insertReturnType)

        // Function 4: DELETE returning Unit
        val deleteReturnType = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val deleteQuery = mockQueryAnnotation("DELETE FROM users WHERE id = :id")
        val deleteParam = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val deleteFn = mockFunction("deleteById", listOf(deleteQuery), listOf(deleteParam), deleteReturnType)

        val repo = mockFoundRepository(
            functions = sequenceOf(selectFn, listFn, insertFn, deleteFn)
        )
        generator.generate(listOf(repo))
        assertMatchesGolden("multi-function-repository")
    }

    @Test
    fun `golden - SELECT returning model with nullable DbMapper property`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty(
                "custom",
                ClassName("com.example", "CustomType").copy(nullable = true),
                nullable = true,
                hasDbMapper = true,
                dbMapperQualifiedName = "com.example.CustomMapper"
            ),
        ))
        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id, custom FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("select-model-nullable-dbmapper")
    }

    @Test
    fun `golden - INSERT with enum and serializable params`() {
        val resolver = mockResolver()
        generator.prepare(resolver)
        val returnTypeRef = mockReturnTypeRef(ClassName("kotlin", "Unit"))
        val queryAnnotation = mockQueryAnnotation("INSERT INTO t (status, payload) VALUES (:status, :payload)")

        val enumDecl = mockSimpleClassDecl(ClassName("com.example", "Status"), ClassKind.ENUM_CLASS)
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Status" }) } returns enumDecl

        val serDecl = mockSimpleClassDecl(ClassName("com.example", "Payload"))
        val serAnnotation = mockk<KSAnnotation>()
        every { serAnnotation.shortName } returns mockKSName("Serializable")
        every { serDecl.annotations } returns sequenceOf(serAnnotation)
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Payload" }) } returns serDecl

        val p1 = mockParameter("status", mockTypeRef(ClassName("com.example", "Status"), enumDecl))
        val p2 = mockParameter("payload", mockTypeRef(ClassName("com.example", "Payload"), serDecl))
        val fn = mockFunction("insert", listOf(queryAnnotation), listOf(p1, p2), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("insert-enum-serializable")
    }

    @Test
    fun `golden - UPDATE RETURNING list of models`() {
        // Covers the bug behind https://… — generator previously emitted
        // `if (row.next()) { results.add(...) }` for `UPDATE … RETURNING *`
        // returning List<Model>, without declaring `results`.
        val resolver = mockResolver(collectionAssignable = true)
        generator.prepare(resolver)
        val innerDecl = mockModelClassDecl("com.example.Job", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty("status", ClassName("kotlin", "String")),
        ))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("com.example", "Job"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation(
            "UPDATE jobs SET status = 'failure' WHERE agent_id = :agentId AND status = 'running' RETURNING *"
        )
        val param = mockParameter("agentId", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("failRunningForAgent", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("update-returning-list-models")
    }

    @Test
    fun `golden - DELETE RETURNING list of models`() {
        val resolver = mockResolver(collectionAssignable = true)
        generator.prepare(resolver)
        val innerDecl = mockModelClassDecl("com.example.User", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty("name", ClassName("kotlin", "String")),
        ))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("com.example", "User"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("DELETE FROM users WHERE deleted = true RETURNING *")
        val fn = mockFunction("purgeDeleted", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("delete-returning-list-models")
    }

    @Test
    fun `golden - INSERT RETURNING list of models`() {
        val resolver = mockResolver(collectionAssignable = true)
        generator.prepare(resolver)
        val innerDecl = mockModelClassDecl("com.example.Row", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty("value", ClassName("kotlin", "String")),
        ))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("com.example", "Row"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation(
            "INSERT INTO rows (value) SELECT v FROM unnest(:values::text[]) AS v RETURNING *"
        )
        val paramTypeRef = mockTypeRef(
            ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String"))
        )
        val param = mockParameter("values", paramTypeRef)
        val fn = mockFunction("insertMany", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("insert-returning-list-models")
    }

    @Test
    fun `golden - DELETE RETURNING list of scalars`() {
        val resolver = mockResolver(collectionAssignable = true)
        generator.prepare(resolver)
        val innerDecl = mockNonClassDecl(ClassName("bosca.serialization", "UUID"))
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(ClassName("bosca.serialization", "UUID"))
        val returnTypeRef = mockCollectionReturnTypeRef(listType, innerDecl)
        val queryAnnotation = mockQueryAnnotation("DELETE FROM tokens WHERE expires < now() RETURNING principal_id")
        val fn = mockFunction("purgeExpired", listOf(queryAnnotation), emptyList(), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("delete-returning-list-scalars")
    }

    @Test
    fun `golden - SELECT returning model with enum and serializable properties`() {
        val resolver = mockResolver()
        generator.prepare(resolver)

        val enumDecl = mockSimpleClassDecl(ClassName("com.example", "Status"), ClassKind.ENUM_CLASS)
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Status" }) } returns enumDecl

        val serDecl = mockSimpleClassDecl(ClassName("com.example", "Payload"))
        val serAnnotation = mockk<KSAnnotation>()
        every { serAnnotation.shortName } returns mockKSName("Serializable")
        every { serDecl.annotations } returns sequenceOf(serAnnotation)
        every { resolver.getClassDeclarationByName(match { it.asString() == "com.example.Payload" }) } returns serDecl

        val modelDecl = mockModelClassDecl("com.example.Item", listOf(
            ModelProperty("id", ClassName("kotlin", "Int")),
            ModelProperty("status", ClassName("com.example", "Status")),
            ModelProperty("payload", ClassName("com.example", "Payload")),
        ))

        // Override constructor param type declarations to be enum/serializable
        val constructor = modelDecl.getAllFunctions().first()
        val statusParam = constructor.parameters[1]
        val statusType = statusParam.type.resolve()
        every { statusType.declaration } returns enumDecl
        val payloadParam = constructor.parameters[2]
        val payloadType = payloadParam.type.resolve()
        every { payloadType.declaration } returns serDecl

        val returnTypeRef = mockReturnTypeRef(ClassName("com.example", "Item"), classDecl = modelDecl)
        val queryAnnotation = mockQueryAnnotation("SELECT id, status, payload FROM items WHERE id = :id")
        val param = mockParameter("id", mockTypeRef(ClassName("kotlin", "Int")))
        val fn = mockFunction("findById", listOf(queryAnnotation), listOf(param), returnTypeRef)
        val repo = mockFoundRepository(functions = sequenceOf(fn))
        generator.generate(listOf(repo))
        assertMatchesGolden("select-model-enum-serializable")
    }
}
