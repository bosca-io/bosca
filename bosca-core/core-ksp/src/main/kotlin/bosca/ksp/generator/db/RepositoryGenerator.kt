package bosca.ksp.generator.db

import bosca.db.annotation.DbConstructor
import bosca.db.annotation.DbMapper
import bosca.db.annotation.Query
import bosca.di.annotation.Generated
import bosca.ksp.Types
import bosca.ksp.ext.isCollection
import bosca.ksp.ext.isPrimitive
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.model.DatabaseModel
import bosca.ksp.visitors.FoundRepository
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class RepositoryGenerator(codeGenerator: CodeGenerator, private val logger: KSPLogger) : AbstractGenerator<FoundRepository>(codeGenerator) {

    private lateinit var collectionType: KSType
    private lateinit var resolver: Resolver

    private companion object {
        /** FQN of the jsonb mapper that takes an explicit serializer (so JSON columns stay native-safe). */
        const val JSONB_MAPPER = "bosca.db.mapper.JsonbMapper"
    }

    private fun KSClassDeclaration.isSerializable(): Boolean =
        annotations.any { it.shortName.asString() == "Serializable" }

    override fun prepare(resolver: Resolver) {
        this.resolver = resolver
        collectionType = resolver.getClassDeclarationByName(resolver.getKSNameFromString("kotlin.collections.Collection"))?.asStarProjectedType() ?: error("Collection class not found")
    }

    override fun generate(items: Collection<FoundRepository>) {
        items.forEach { repository ->
            logger.info("Generating repository for ${repository.repository}")
            val repositoryClassName = repository.repository as ClassName
            val repositoryImplClassName = ClassName(repositoryClassName.packageName, "${repositoryClassName.simpleName}Impl")

            val classBuilder = TypeSpec
                .classBuilder(repositoryImplClassName)
                .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                .addProperty(
                    PropertySpec
                        .builder("json", Types.Json, KModifier.PRIVATE)
                        .delegate("lazy { runBlocking { get<Json>() } }")
                        .build()
                )
                .addProperty(
                    PropertySpec
                        .builder("mappers", Types.DefaultMappers, KModifier.PRIVATE)
                        .delegate("lazy { %T(json) }", Types.DefaultMappers)
                        .build()
                )
                .addSuperinterface(repositoryClassName)
                .addOriginatingKSFile(repository.classDeclaration.containingFile ?: error("No containing file"))

            val fileBuilder = FileSpec.builder(repositoryImplClassName)
                .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\", \"USELESS_CAST\"").build())
                .addImport("kotlinx.coroutines.reactive", "awaitFirstOrNull")
                .addImport("kotlinx.coroutines.reactive", "asFlow")
                .addImport("kotlinx.coroutines.flow", "firstOrNull")
                .addImport("kotlinx.coroutines.flow", "map")
                .addImport("kotlinx.coroutines", "withContext")
                .addImport("bosca.db", "DatabaseDispatcher")
                .addImport("kotlinx.coroutines", "runBlocking")
                .addImport(Types.UUID, "")
                .addImport(Types.ProviderRegistry, "get")

            repository.classDeclaration.getAllFunctions().forEach { function ->
                function.annotations
                    .firstOrNull { annotation -> annotation.shortName.asString() == (Query::class.simpleName ?: error("Query class not found")) }
                    ?.let { query ->
                        if (function.parameters.size > 1 && function.parameters.any {
                                val resolve = it.type.resolve()
                                val declaration = resolve.declaration
                                val typeName = resolve.toTypeName()
                                declaration is KSClassDeclaration &&
                                    declaration.classKind != ClassKind.ENUM_CLASS &&
                                    !typeName.isPrimitive &&
                                    !typeName.isCollection &&
                                    !declaration.isSerializable()
                            }) {
                            error("Query functions do not support multiple model parameters, only multiple primitive parameters: ${function.simpleName.asString()}")
                        }
                        val returnUpdateCount = query.arguments
                            .firstOrNull { it.name?.asString() == "returnUpdateCount" }
                            ?.value as? Boolean == true
                        val query = try {
                            QueryCompiler.compileQuery(query.arguments.first().value.toString())
                        } catch (_: Exception) {
                            error("failed to parse query (${query.arguments.first().value.toString()}): ${function.simpleName.asString()}")
                        }
                        val bindings = if (function.parameters.size == 1) {
                            val parameter = function.parameters.first()
                            val parameterType = parameter.type.resolve()
                            val parameterTypeName = parameterType.toTypeName()
                            val declaration = parameterType.declaration
                            if (parameterTypeName.isPrimitive || (declaration is KSClassDeclaration && declaration.classKind == ClassKind.ENUM_CLASS) || parameterTypeName.isCollection) {
                                toBindings(query, null, null, fileBuilder, function.parameters)
                            } else if (parameterType.declaration is KSClassDeclaration) {
                                val model = DatabaseModel(parameterType.declaration as KSClassDeclaration, logger)
                                toBindings(query, model, parameter.name?.asString() ?: error("missing name"), fileBuilder, function.parameters)
                            } else {
                                error("Unsupported parameter type: ${parameterType.toTypeName()}")
                            }
                        } else {
                            toBindings(query, null, null, fileBuilder, function.parameters)
                        }
                        val parameters = toParameters(function)
                        val returnTypeClass = when (query.type) {
                            QueryType.SELECT -> toQuery(function, query, bindings, classBuilder, parameters)
                            QueryType.INSERT, QueryType.UPDATE, QueryType.DELETE -> toUpdate(function, query, bindings, classBuilder, parameters, returnUpdateCount)
                        }
                        if (returnTypeClass is ParameterizedTypeName) {
                            fileBuilder.addImport(returnTypeClass.rawType.packageName, returnTypeClass.rawType.simpleName)
                            returnTypeClass.typeArguments.forEach {
                                if (it is ClassName) {
                                    fileBuilder.addImport(it.packageName, it.simpleName)
                                }
                            }
                        } else if (returnTypeClass is ClassName) {
                            fileBuilder.addImport(returnTypeClass.packageName, returnTypeClass.simpleName)
                        }
                    }
            }

            fileBuilder
                .addImport("bosca.db", "connection")
                .addImport("kotlinx.coroutines", "ExperimentalCoroutinesApi")
                .addImport("kotlinx.coroutines.flow", "toList")
                .addImport("kotlinx.coroutines.flow", "toSet")
                .addImport("bosca.serialization.JsonConverter", "toJsonElement")
                .addImport(KClass::class.asClassName(), "")
                .addType(classBuilder.build())
                .build()
                .writeTo(codeGenerator, true)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun StringBuilder.defaultBindMappers(typeName: TypeName, parameterOffset: Int, propertyName: String) {
        val className = when (typeName) {
            is ParameterizedTypeName -> typeName.rawType.canonicalName
            is ClassName -> typeName.canonicalName
            is TypeVariableName -> typeName.name
            else -> error("unsupported type: $typeName")
        }
        append("|   mappers.")
        when (className) {
            List::class.asClassName().canonicalName -> append("array.bind(Array::class, listOf(${((typeName as ParameterizedTypeName).typeArguments.first() as ClassName).canonicalName}::class)")
            String::class.asClassName().canonicalName -> append("string.bind(String::class, emptyList<KClass<*>>()")
            "bosca.serialization.UUID",
            Uuid::class.asClassName().canonicalName -> append("uuid.bind(UUID::class, emptyList<KClass<*>>()")

            Int::class.asClassName().canonicalName -> append("int.bind(Int::class, emptyList<KClass<*>>()")
            Long::class.asClassName().canonicalName -> append("long.bind(Long::class, emptyList<KClass<*>>()")
            Float::class.asClassName().canonicalName -> append("float.bind(Float::class, emptyList<KClass<*>>()")
            Double::class.asClassName().canonicalName -> append("double.bind(Double::class, emptyList<KClass<*>>()")
            Boolean::class.asClassName().canonicalName -> append("boolean.bind(Boolean::class, emptyList<KClass<*>>()")
            ByteArray::class.asClassName().canonicalName -> append("byteArray.bind(ByteArray::class, emptyList<KClass<*>>()")
            "java.time.OffsetDateTime",
            "bosca.serialization.OffsetDateTime" -> append("offsetDateTime.bind(bosca.serialization.OffsetDateTime::class, emptyList<KClass<*>>()")

            "java.time.LocalDateTime",
            "bosca.serialization.LocalDateTime" -> append("localDateTime.bind(bosca.serialization.LocalDateTime::class, emptyList<KClass<*>>()")

            "kotlin.time.Instant" -> append("instant.bind(kotlin.time.Instant::class, emptyList<KClass<*>>()")

            Types.JsonElement.canonicalName -> append("jsonElement.bind(kotlinx.serialization.json.JsonElement::class, emptyList<KClass<*>>()")
            else -> {
                val declaration = resolver.getClassDeclarationByName(resolver.getKSNameFromString(className))
                if (declaration?.classKind == ClassKind.ENUM_CLASS) {
                    append("enum($className::class).bind($className::class, emptyList<KClass<*>>()")
                } else if (declaration is KSClassDeclaration && declaration.isSerializable()) {
                    append("serializable.bind($className.serializer()")
                } else {
                    error("unsupported type: $typeName")
                }
            }
        }
        if (className == List::class.asClassName().canonicalName) {
            append(", stmt, ${parameterOffset + 1}, $propertyName.toTypedArray())\n")
        } else {
            append(", stmt, ${parameterOffset + 1}, $propertyName)\n")
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun StringBuilder.defaultMapMappers(constructor: KSFunctionDeclaration, typeName: TypeName, parameterName: String?, columnName: String?, columnIndex: Int? = null): StringBuilder {
        val className = when (typeName) {
            is ParameterizedTypeName -> typeName.rawType.canonicalName
            is ClassName -> typeName.canonicalName
            is TypeVariableName -> typeName.name
            else -> error("unsupported type: $typeName")
        }
        if (parameterName != null) {
            append("|   val $parameterName = ")
        } else {
            append("|   ")
        }
        append("mappers.")
        when (className) {
            List::class.asClassName().canonicalName -> append("array.map(Array::class, listOf(${((typeName as ParameterizedTypeName).typeArguments.first() as ClassName).canonicalName}::class)")
            String::class.asClassName().canonicalName -> append("string.map(String::class, emptyList<KClass<*>>()")
            "bosca.serialization.UUID",
            Uuid::class.asClassName().canonicalName -> append("uuid.map(UUID::class, emptyList<KClass<*>>()")

            "kotlin.ByteArray" -> append("byteArray.map(ByteArray::class, emptyList<KClass<*>>()")

            Int::class.asClassName().canonicalName -> append("int.map(Int::class, emptyList<KClass<*>>()")
            Long::class.asClassName().canonicalName -> append("long.map(Long::class, emptyList<KClass<*>>()")
            Float::class.asClassName().canonicalName -> append("float.map(Float::class, emptyList<KClass<*>>()")
            Double::class.asClassName().canonicalName -> append("double.map(Double::class, emptyList<KClass<*>>()")
            Boolean::class.asClassName().canonicalName -> append("boolean.map(Boolean::class, emptyList<KClass<*>>()")
            "java.time.OffsetDateTime",
            "bosca.serialization.OffsetDateTime" -> append("offsetDateTime.map(bosca.serialization.OffsetDateTime::class, emptyList<KClass<*>>()")

            "java.time.LocalDateTime",
            "bosca.serialization.LocalDateTime" -> append("localDateTime.map(bosca.serialization.LocalDateTime::class, emptyList<KClass<*>>()")

            Instant::class.asClassName().canonicalName -> append("instant.map(kotlin.time.Instant::class, emptyList<KClass<*>>()")

            Types.JsonElement.canonicalName -> append("jsonElement.map(kotlinx.serialization.json.JsonElement::class, emptyList<KClass<*>>()")
            else -> {
                val declaration = resolver.getClassDeclarationByName(resolver.getKSNameFromString(className))
                if (declaration?.classKind == ClassKind.ENUM_CLASS) {
                    append("enum($className::class).map($className::class, emptyList<KClass<*>>()")
                } else if (declaration is KSClassDeclaration && declaration.isSerializable()) {
                    append("serializable.map($className.serializer()")
                } else {
                    error("unsupported type: $typeName used in ${constructor.parentDeclaration?.qualifiedName?.asString()}.${constructor.simpleName.asString()}")
                }
            }
        }
        if (columnIndex != null) {
            append(", row, ${columnIndex + 1})")
        } else {
            if (className == List::class.asClassName().canonicalName) {
                append(", row, \"${columnName}\")")
            } else {
                append(", row, \"${columnName}\")")
            }
        }
        when (className) {
            List::class.asClassName().canonicalName -> append("?.toList() as $typeName")
        }
        if (parameterName != null && !typeName.isNullable) {
            append(" ?: error(\"$parameterName is required\")")
        }
        return this
    }

    /**
     * Builds the explicit kotlinx serializer expression for [typeName] (e.g.
     * `ListSerializer(Foo.serializer())`), fully qualified so no imports are needed. Never resolves a
     * serializer reflectively, so the generated code is safe in the GraalVM native image.
     */
    private fun jsonbSerializerExpr(typeName: TypeName): String {
        val nonNull = typeName.copy(nullable = false)
        return when (nonNull) {
            is ParameterizedTypeName -> when (nonNull.rawType.canonicalName) {
                "kotlin.collections.List" -> "kotlinx.serialization.builtins.ListSerializer(${jsonbSerializerExpr(nonNull.typeArguments.first())})"
                "kotlin.collections.Set" -> "kotlinx.serialization.builtins.SetSerializer(${jsonbSerializerExpr(nonNull.typeArguments.first())})"
                "kotlin.collections.Map" -> "kotlinx.serialization.builtins.MapSerializer(${jsonbSerializerExpr(nonNull.typeArguments[0])}, ${jsonbSerializerExpr(nonNull.typeArguments[1])})"
                else -> "${nonNull.rawType.canonicalName}.serializer()"
            }
            is ClassName -> "${nonNull.canonicalName}.serializer()"
            else -> error("@DbMapper($JSONB_MAPPER) does not support type: $typeName")
        }
    }

    /** Emits a parameter bind through [dbMapperFqn]; [JSONB_MAPPER] uses the explicit-serializer jsonb path. */
    private fun dbMapperBind(dbMapperFqn: String, typeName: TypeName, offset: Int, propertyName: String): String {
        val nonNull = typeName.copy(nullable = false)
        return if (dbMapperFqn == JSONB_MAPPER) {
            "|   $JSONB_MAPPER.bind(json, ${jsonbSerializerExpr(nonNull)}, stmt, ${offset + 1}, $propertyName)\n"
        } else {
            "|   $dbMapperFqn.bind($nonNull::class, emptyList<KClass<*>>(), stmt, ${offset + 1}, $propertyName)\n"
        }
    }

    /** Emits a column read through [dbMapperFqn]; [JSONB_MAPPER] uses the explicit-serializer jsonb path. */
    private fun dbMapperMap(dbMapperFqn: String, typeName: TypeName, parameterName: String, columnName: String): String {
        val nonNull = typeName.copy(nullable = false)
        return if (dbMapperFqn == JSONB_MAPPER) {
            "|   val $parameterName = $JSONB_MAPPER.map(json, ${jsonbSerializerExpr(nonNull)}, row, \"$columnName\")"
        } else {
            "|   val $parameterName = $dbMapperFqn.map($nonNull::class, emptyList<KClass<*>>(), row, \"$columnName\")"
        }
    }

    private fun toBindings(query: CompiledQuery, model: DatabaseModel?, parameterName: String?, fileBuilder: FileSpec.Builder, parameters: List<KSValueParameter>): String = buildString {
        query.parameters.forEachIndexed { index, parameter ->
            val propertyName = "${parameterName?.let { "$it." } ?: ""}${parameter.name}"
            if (model != null) {
                if (!model.columnNames.containsKey(parameter.name)) error("missing model column for property ${parameter.name} for ${model.name} and query: ${query.originalQuery}")
                val propertyType = model.propertyTypes[parameter.name] ?: error("missing model property type for property ${parameter.name} for ${model.name} and query: ${query.originalQuery}")
                val typeName = propertyType.copy(nullable = false)
                val dbMapper = model.dbMapper[parameter.name]?.let {
                    it.declaration.qualifiedName?.asString() ?: error("DbMapper annotation value is not a class")
                }
                if (dbMapper != null) {
                    append(dbMapperBind(dbMapper, typeName, parameter.offset, propertyName))
                } else {
                    defaultBindMappers(typeName, parameter.offset, propertyName)
                }
                if (propertyType is ParameterizedTypeName) {
                    fileBuilder.addImport(propertyType.rawType, "")
                    propertyType.typeArguments.forEach {
                        fileBuilder.addImport(it as ClassName, "")
                    }
                } else if (propertyType is ClassName) {
                    fileBuilder.addImport(propertyType, "")
                }
            } else {
                val type = parameters.firstOrNull { it.name?.asString() == parameter.name }?.type?.resolve() ?: error("missing parameter type for parameter ${parameter.name} ${parameter.offset} and query: ${query.originalQuery}")
                val typeName = type.toTypeName().copy(nullable = false)
                val dbMapper = type.declaration.annotations
                    .firstOrNull { it.shortName.asString() == DbMapper::class.simpleName }
                    ?.arguments
                    ?.first()
                    ?.value
                    ?.let {
                        (it as KSType).declaration.qualifiedName?.asString() ?: error("DbMapper annotation value is not a class")
                    }
                if (dbMapper != null) {
                    append(dbMapperBind(dbMapper, typeName, parameter.offset, propertyName))
                } else {
                    defaultBindMappers(typeName, parameter.offset, propertyName)
                }
            }
        }
    }

    private fun toParameters(function: KSFunctionDeclaration): List<ParameterSpec> {
        return function.parameters.map {
            val type = it.type.resolve().toTypeName()
            ParameterSpec
                .builder(it.name?.asString() ?: error("missing parameter name"), type)
                .build()
        }
    }

    private fun toUpdate(function: KSFunctionDeclaration, query: CompiledQuery, bindings: String, classBuilder: TypeSpec.Builder, parameters: List<ParameterSpec>, returnUpdateCount: Boolean = false): TypeName {
        val returnType = ReturnType(
            function.returnType ?: error("missing return type"),
            collectionType,
            logger
        )
        val isUnit = returnType.typeName is ClassName && returnType.typeName.packageName == "kotlin" && returnType.typeName.simpleName == "Unit"
        // returnUpdateCount: the query is a DML statement with no `returning` clause whose result is the
        // affected-row count, so it must run via executeUpdate() (executeQuery() throws on a no-result
        // statement). executeUpdate() yields an Int; widen to Long when the function returns Long.
        if (returnUpdateCount && !isUnit) {
            val countExpr = if (returnType.typeName.copy(nullable = false) == Long::class.asClassName()) {
                "stmt.executeUpdate().toLong()"
            } else {
                "stmt.executeUpdate()"
            }
            classBuilder.addFunction(
                FunSpec.builder(function.simpleName.asString())
                    .addParameters(parameters)
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .addAnnotations(returnType.annotations)
                    .addCode(
                        """
                        |return withContext(DatabaseDispatcher) {
                        |   val connection = connection()
                        |   connection.useStatement(%S) { stmt ->
                        |       ${if (query.type != QueryType.SELECT) "connection.markNeedsCommitOrRollback()" else ""}
                        $bindings
                        |       $countExpr
                        |   }
                        |}
                        |
                        """.trimMargin(),
                        query.sql,
                    )
                    .returns(returnType.typeName)
                    .build()
            )
            return returnType.typeName
        }
        if (isUnit) {
            classBuilder.addFunction(
                FunSpec.builder(function.simpleName.asString())
                    .addParameters(parameters)
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .addAnnotations(returnType.annotations)
                    .addCode(
                        """
                        |return withContext(DatabaseDispatcher) {
                        |   val connection = connection()
                        |   connection.useStatement(%S) { stmt ->
                        |       ${if (query.type != QueryType.SELECT) "connection.markNeedsCommitOrRollback()" else ""}
                        $bindings
                        |       stmt.execute()
                        |   }
                        |}
                        |
                        """.trimMargin(),
                        query.sql,
                    )
                    .returns(returnType.typeName)
                    .build()
            )
        } else if (returnType.rawTypeName.isPrimitive || returnType.type !is KSClassDeclaration || returnType.model == null) {
            val funSpec = FunSpec.builder(function.simpleName.asString())
                .addParameters(parameters)
                .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                .addAnnotations(returnType.annotations)
                .returns(returnType.typeName)
            if (returnType.isCollection) {
                funSpec.addCode(
                    """
                    |return withContext(DatabaseDispatcher) {
                    |   val connection = connection()
                    |   connection.useStatement(%S) { stmt ->
                    |       ${if (query.type != QueryType.SELECT) "connection.markNeedsCommitOrRollback()" else ""}
                    $bindings
                    |       stmt.executeQuery().use { row ->
                    |           val results = ${if (returnType.resultCollectionType == CollectionType.SET) "mutableSetOf" else "mutableListOf"}<%T>()
                    |           while (row.next()) {
                    |               results.add(
                    ${StringBuilder().defaultMapMappers(function, returnType.rawTypeName, null, null, 0)}${if (returnType.typeName.isNullable) "" else " ?: error(\"row value is null\")"}
                    |               )
                    |           }
                    |           return@useStatement results
                    |       }
                    |   }
                    |}
                    |
                    """.trimMargin(),
                    query.sql,
                    returnType.rawTypeName.copy(nullable = false),
                )
            } else {
                funSpec.addCode(
                    """
                    |return withContext(DatabaseDispatcher) {
                    |   val connection = connection()
                    |   connection.useStatement(%S) { stmt ->
                    |       ${if (query.type != QueryType.SELECT) "connection.markNeedsCommitOrRollback()" else ""}
                    $bindings
                    |       stmt.executeQuery().use { row ->
                    |           return@useStatement if (row.next()) {
                    ${StringBuilder().defaultMapMappers(function, returnType.typeName, null, null, 0)}${if (!returnType.nullable) " ?: error(\"returned null\")" else ""}
                    |           } else {
                    |               ${if (returnType.nullable) "null" else "error(\"returned null\")"}
                    |           }
                    |       }
                    |   }
                    |}
                    |
                    """.trimMargin(),
                    query.sql,
                )
            }
            classBuilder.addFunction(funSpec.build())
        } else {
            val constructor = toConstructor(returnType.type.getAllFunctions().firstOrNull {
                it.annotations.any { annotation -> annotation.shortName.asString() == DbConstructor::class.simpleName }
            } ?: returnType.type.primaryConstructor ?: error("No constructor found: ${returnType.type.simpleName.asString()}"), returnType)
            val funSpec = FunSpec.builder(function.simpleName.asString())
                .addParameters(parameters)
                .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                .addAnnotations(returnType.annotations)
                .returns(returnType.typeName)
            if (returnType.isCollection) {
                funSpec.addCode(
                    """
                    |return withContext(DatabaseDispatcher) {
                    |   val connection = connection()
                    |   connection.useStatement(%S) { stmt ->
                    |       ${if (query.type != QueryType.SELECT) "connection.markNeedsCommitOrRollback()" else ""}
                    $bindings
                    |       stmt.executeQuery().use { row ->
                    |           val results = ${if (returnType.resultCollectionType == CollectionType.SET) "mutableSetOf" else "mutableListOf"}<%T>()
                    |           while (row.next()) {
                    $constructor
                    |           }
                    |           return@useStatement results
                    |       }
                    |   }
                    |}
                    |
                    """.trimMargin(),
                    query.sql,
                    returnType.rawTypeName.copy(nullable = false),
                    returnType.rawTypeName.copy(nullable = false),
                )
            } else {
                funSpec.addCode(
                    """
                    |return withContext(DatabaseDispatcher) {
                    |   val connection = connection()
                    |   connection.useStatement(%S) { stmt ->
                    |       ${if (query.type != QueryType.SELECT) "connection.markNeedsCommitOrRollback()" else ""}
                    $bindings
                    |       stmt.executeQuery().use { row ->
                    |           if (row.next()) {
                    $constructor
                    |           }
                    |           ${if (returnType.nullable) "return@useStatement null" else "error(\"returned null\")"}
                    |       }
                    |   }
                    |}
                    |
                    """.trimMargin(),
                    query.sql,
                    returnType.rawTypeName,
                )
            }
            classBuilder.addFunction(funSpec.build())
        }
        return returnType.typeName
    }

    private fun toQuery(function: KSFunctionDeclaration, query: CompiledQuery, bindings: String, classBuilder: TypeSpec.Builder, parameters: List<ParameterSpec>): TypeName {
        val returnType = ReturnType(
            function.returnType ?: error("missing return type"),
            collectionType,
            logger
        )
        // rawTypeName is the element type for collections. Scalar elements (String, UUID, …) must
        // take the column-mapping path: they resolve as KSClassDeclarations with a synthesizable
        // DatabaseModel, and the row-model constructor path would emit a no-arg constructor call
        // (e.g. `String()`) that silently discards the column value.
        if (returnType.type !is KSClassDeclaration || returnType.model == null || returnType.rawTypeName.isPrimitive) {
            classBuilder.addFunction(
                FunSpec.builder(function.simpleName.asString())
                    .addParameters(parameters)
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .addAnnotations(returnType.annotations)
                    .addCode(
                        if (returnType.isCollection) {
                            """
                            |return withContext(DatabaseDispatcher) {
                            |   val connection = connection()
                            |   connection.useStatement(%S) { stmt ->
                            |${if (query.type != QueryType.SELECT) "connection.markNeedsCommitOrRollback()" else ""}
                            $bindings
                            |       stmt.executeQuery().use { row ->
                            |           val results = ${if (returnType.resultCollectionType == CollectionType.SET) "mutableSetOf" else "mutableListOf"}<%T>()
                            |           while (row.next()) {
                            |               results.add(
                            ${StringBuilder().defaultMapMappers(function, returnType.rawTypeName, null, null, 0)}${if (returnType.typeName.isNullable) "" else " ?: error(\"row value is null\")"}
                            |               )
                            |           }
                            |           return@useStatement results
                            |       }
                            |   }
                            |}
                            """.trimMargin()
                        } else {
                            """
                            |return withContext(DatabaseDispatcher) {
                            |   val connection = connection()
                            |   connection.useStatement(%S) { stmt ->
                            |       ${if (query.type != QueryType.SELECT) "connection.markNeedsCommitOrRollback()" else ""}
                            $bindings
                            |       stmt.executeQuery().use { row ->
                            |           // %T
                            |           return@useStatement if (row.next()) {
                            ${StringBuilder().defaultMapMappers(function, returnType.typeName, null, null, 0)}${if (returnType.typeName.isNullable) "" else " ?: error(\"row value is null\")"}
                            |           } else {
                            |               ${if (returnType.nullable) "null" else "error(\"returned null\")"}
                            |           }
                            |       }
                            |   }
                            |}
                            """.trimMargin()
                        },
                        query.sql,
                        returnType.rawTypeName.copy(nullable = false),
                    )
                    .returns(returnType.typeName)
                    .build()
            )
        } else {
            val constructor = returnType.type.getAllFunctions().firstOrNull {
                it.annotations.any { annotation -> annotation.shortName.asString() == DbConstructor::class.simpleName }
            } ?: returnType.type.primaryConstructor ?: error("No constructor found: ${returnType.type.simpleName.asString()}")
            classBuilder.addFunction(
                FunSpec.builder(function.simpleName.asString())
                    .addParameters(parameters)
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .addAnnotations(returnType.annotations)
                    .let {
                        if (returnType.typeName.isPrimitive) {
                            it.addCode(
                                """
                                |return withContext(DatabaseDispatcher) {
                                |   val connection = connection()
                                |   connection.useStatement(%S) { stmt ->
                                |       ${if (query.type != QueryType.SELECT) "connection.markNeedsCommitOrRollback()" else ""}
                                $bindings
                                |       stmt.executeQuery().use { row ->
                                |           return@useStatement if (row.next()) { 
                                ${StringBuilder().defaultMapMappers(constructor, returnType.typeName, null, null, 0)} ${if (!returnType.nullable) " ?: error(\"returned null\")" else ""}
                                |           } else {
                                |               ${if (returnType.nullable) "null" else "error(\"returned null\")"}
                                |           }
                                |       }
                                |   }
                                |}
                                |
                                """.trimMargin(),
                                query.sql,
                            )
                        } else {
                            it.addCode(
                                """
                                |return withContext(DatabaseDispatcher) {
                                |   val connection = connection()
                                |   connection.useStatement(%S) { stmt ->
                                |       ${if (query.type != QueryType.SELECT) "connection.markNeedsCommitOrRollback()" else ""}
                                $bindings
                                |       stmt.executeQuery().use { row ->
                                ${if (returnType.isCollection) "|           val results = ${if (returnType.resultCollectionType == CollectionType.SET) "mutableSetOf" else "mutableListOf"}<%T>()" else "// %T"}
                                |           ${if (returnType.isCollection) "while" else "if"} (row.next()) {
                                ${toConstructor(constructor, returnType)} 
                                |           }
                                |           ${if (returnType.isCollection) "return@useStatement results" else if (returnType.nullable) "return@useStatement null" else "error(\"returned null\")"}
                                |       }
                                |   }
                                |}
                                |
                                """.trimMargin(),
                                query.sql,
                                returnType.rawTypeName.copy(nullable = false),
                                returnType.rawTypeName.copy(nullable = false)
                            )
                        }
                    }
                    .returns(returnType.typeName)
                    .build()
            )
        }
        return returnType.typeName
    }

    private fun toConstructor(constructor: KSFunctionDeclaration, resultType: ReturnType) =
        buildString {
            val model = resultType.model ?: error("missing model for constructor: ${constructor.simpleName.asString()}")
            constructor.parameters.forEach { parameter ->
                val typeName = parameter.type.toTypeName()
                val parameterName = parameter.name?.asString() ?: error("missing parameter name")
                val columnName = model.columnNames[parameterName] ?: return@forEach
                // Prefer DatabaseModel's resolution (property + property-type, incl. property-level
                // @DbMapper), falling back to the constructor parameter's own type annotation. For a real
                // data class the property and its constructor parameter share the same type, so these
                // agree; the fallback keeps parity with the bind path's parameter-type resolution.
                val dbMapperKsType = model.dbMapper[parameterName] ?: (
                    parameter.type.resolve().declaration.annotations
                        .firstOrNull { it.shortName.asString() == DbMapper::class.simpleName }
                        ?.arguments?.first()?.value as KSType?
                    )
                val dbMapper = dbMapperKsType?.let {
                    it.declaration.qualifiedName?.asString() ?: error("DbMapper annotation value is not a class")
                }
                if (dbMapper != null) {
                    append(dbMapperMap(dbMapper, typeName, parameterName, columnName))
                    if (!typeName.isNullable) {
                        append(" ?: error(\"$parameterName is required\")")
                    }
                    append("\n")
                } else {
                    defaultMapMappers(constructor, typeName, parameterName, columnName)
                    append("\n")
                }
                append("\n")
            }
            if (!resultType.isCollection) {
                append("|               return@useStatement %T(\n")
            } else {
                append("|               results.add(%T(\n")
            }
            constructor.parameters.forEachIndexed { index, parameter ->
                val parameterName = parameter.name?.asString() ?: error("missing parameter name")
                if (!model.columnNames.containsKey(parameterName)) return@buildString
                append("|                   ")
                append(parameterName)
                if (index < constructor.parameters.size - 1) {
                    append(", ")
                }
                append("\n")
            }
            if (!resultType.isCollection) {
                append("|               )\n")
            } else {
                append("|               ))\n")
            }
        }
}

