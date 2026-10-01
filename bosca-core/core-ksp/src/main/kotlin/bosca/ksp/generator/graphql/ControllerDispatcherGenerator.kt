package bosca.ksp.generator.graphql

import bosca.di.annotation.Generated
import bosca.graphql.annotations.Field
import bosca.ksp.Types
import bosca.ksp.ext.isCollection
import bosca.ksp.ext.isFlow
import bosca.ksp.ext.isJsonElement
import bosca.ksp.ext.isOffsetDateTime
import bosca.ksp.ext.isPrimitive
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundTypeController
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import bosca.graphql.server.ResolverContext
import bosca.graphql.server.TypeRuntimeWiring

class ControllerDispatcherGenerator(codeGenerator: CodeGenerator, private val logger: KSPLogger) : AbstractGenerator<FoundTypeController>(codeGenerator) {

    override fun generate(items: Collection<FoundTypeController>) {
        items.forEach { (targetClass, typeName, _, _, dispatcher, _, classDeclaration) ->
            val batchTypes = mutableListOf<TypeSpec>()

            val classBuilder = TypeSpec
                .classBuilder(dispatcher as ClassName)
                .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                .addSuperinterface(Types.Dispatcher)
                .addOriginatingKSFile(classDeclaration.containingFile ?: error("No containing file"))
                .primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter("controller", classDeclaration.toClassName())
                        .build()
                ).addProperty(
                    PropertySpec.builder("controller", classDeclaration.toClassName())
                        .initializer("controller")
                        .addModifiers(KModifier.PRIVATE)
                        .build()
                ).addProperty(
                    PropertySpec.builder("json", Types.Json)
                        .addModifiers(KModifier.PRIVATE)
                        .delegate("lazy { runBlocking { get<Json>() } }")
                        .build()
                ).addProperty(
                    PropertySpec.builder("tracer", Types.Tracer)
                        .addModifiers(KModifier.PRIVATE)
                        .delegate("lazy { runBlocking { get<Tracer>() } }")
                        .build()
                )

            classDeclaration.getAllFunctions().forEach { function ->
                if (function.annotations.any { annotation -> annotation.shortName.asString() == (Field::class.simpleName ?: error("Field class not found")) }) {
                    val returnType = function.returnType?.toTypeName() ?: error("No return type found")
                    val batchParameter = getBatchType(function)
                    val type = if (batchParameter != null) {
                        val parameterType = batchParameter.type.resolve()
                        val batchType = (parameterType.arguments.last().type?.resolve() ?: error("missing type")).toTypeName()
                        Types.SuspendDataFetcher.parameterizedBy(batchType.copy(nullable = true))
                    } else if (function.modifiers.contains(Modifier.SUSPEND)) {
                        Types.SuspendDataFetcher.parameterizedBy(returnType)
                    } else if (returnType.isFlow) {
                        Types.FlowDataFetcher.parameterizedBy((returnType as ParameterizedTypeName).typeArguments.first())
                    } else {
                        Types.PropertyDataFetcher.parameterizedBy(returnType)
                    }
                    classBuilder.addProperty(newFieldProperty(function, targetClass, type, batchParameter))
                }
            }
            classBuilder.addProperty(newTypeProperty(classDeclaration, typeName))
            FileSpec.builder(dispatcher)
                .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\"").build())
                .addImport("bosca.ext", "anyToJsonElement")
                .addImport("kotlinx.serialization.json.Json", "")
                .addImport("kotlinx.serialization.json.JsonNull", "")
                .addImport("bosca.di.ProviderRegistry", "get")
                .addImport("kotlinx.coroutines", "runBlocking")
                .addImport("bosca.graphql.Batch", "")
                .addImport("bosca.graphql.BatchLoaderEnvironment", "")
                .addImport("bosca.graphql.PropertyDataFetcher", "")
                .addImport("bosca.graphql.SuspendDataFetcher", "")
                .addImport("kotlinx.serialization.builtins", "ListSerializer")
                .addImport("kotlinx.serialization.builtins", "serializer")
                .addImport("bosca.serialization.OffsetDateTimeSerializer", "")
                .addImport("bosca.graphql.BatchContext", "")
                .addImport(Types.ServerCall, "")
                .addImport(Types.AuthenticationContext, "")
                .addType(classBuilder.build())
                .addTypes(batchTypes)
                .build()
                .writeTo(codeGenerator, true)
        }
    }

    internal fun newTypeProperty(classDeclaration: KSClassDeclaration, typeName: String): PropertySpec {
        val args = mutableListOf<String>()
        return PropertySpec
            .builder("type", TypeRuntimeWiring::class)
            .addModifiers(KModifier.OVERRIDE)
            .initializer(
                buildString {
                    append("TypeRuntimeWiring.newTypeWiring(%S)\n")
                    args.add(typeName)
                    classDeclaration.getAllFunctions().forEach {
                        val field = it.annotations.firstOrNull { annotation -> annotation.shortName.asString() == (Field::class.simpleName ?: error("Field class not found")) }
                        if (field != null) {
                            args.add(field.arguments.firstOrNull { arg -> arg.name?.asString() == "name" }?.value?.toString()?.takeIf { it.isNotBlank() } ?: it.simpleName.asString())
                            args.add(it.simpleName.asString())
                            append(".field(%S, %LDataFetcher)\n")
                        }
                    }
                    append(".build()")
                }, *args.toTypedArray()
            )
            .build()
    }

    internal fun newFieldProperty(function: KSFunctionDeclaration, targetClass: KSType, type: TypeName, batchTypeParameter: KSValueParameter?): PropertySpec {
        val args = mutableListOf<Any>()
        return PropertySpec
            .builder("${function.simpleName.asString()}DataFetcher", type, KModifier.PRIVATE)
            .initializer(
                buildString {
                    append("$type(%S, tracer) { environment, authenticationContext ->\n")
                    args.add(function.simpleName.asString())
                    if (batchTypeParameter != null) {
                        val parameterName = batchTypeParameter.name?.asString() ?: error("Parameter name not found")
                        val batchType = batchTypeParameter.type.resolve()
                        appendBatchFunction(function, parameterName, targetClass, args, batchType)
                    } else {
                        function.parameters.forEach { parameter ->
                            val parameterName = parameter.name?.asString() ?: error("Parameter name not found")
                            val resolvedType = parameter.type.resolve()
                            when (val type = resolvedType.toTypeName()) {
                                is ParameterizedTypeName -> {
                                    if (type.rawType.canonicalName == targetClass.toClassName().canonicalName) {
                                        appendSource(parameterName, type, args)
                                    } else {
                                        appendArgument(type, parameterName, args)
                                    }
                                }

                                is ClassName -> {
                                    if (type.canonicalName == ResolverContext::class.qualifiedName || type.canonicalName == "bosca.graphql.DataFetchingEnvironment") return@forEach
                                    if (type.canonicalName == Types.AuthenticationContext.canonicalName) return@forEach
                                    if (type.canonicalName == Types.ServerCall.canonicalName) {
                                        append("val call = environment.context.getAs<ServerCall>(\"call\") ?: error(\"Missing call\")\n")
                                    } else if (type.canonicalName == targetClass.toClassName().canonicalName) {
                                        appendSource(parameterName, type, args)
                                    } else {
                                        appendArgument(type, parameterName, args)
                                    }
                                }

                                else -> TODO("unsupported field property")
                            }
                        }
                        appendControllerFunction(function, args)
                    }
                    append("}\n")
                }, *args.toTypedArray()
            )
            .build()
    }

    internal fun StringBuilder.appendControllerFunction(function: KSFunctionDeclaration, args: MutableList<Any>) {
        append("val result = controller.%L(")
        args.add(function.simpleName.asString())
        function.parameters.forEachIndexed { index, parameter ->
            val parameterName = parameter.name?.asString() ?: error("Parameter name not found")
            val resolved = parameter.type.resolve()
            val type = resolved.toTypeName()
            if (type is ClassName && (type.canonicalName == ResolverContext::class.qualifiedName || type.canonicalName == "bosca.graphql.DataFetchingEnvironment")) {
                append("%L = environment")
                args.add(parameterName)
                if (index < function.parameters.size - 1) append(", ")
                return@forEachIndexed
            }
            if (type is ClassName && type.canonicalName == Types.ServerCall.canonicalName) {
                append("%L = call")
                args.add(parameterName)
                if (index < function.parameters.size - 1) append(", ")
                return@forEachIndexed
            }
            if (type is ClassName && type.canonicalName == Types.AuthenticationContext.canonicalName) {
                append("%L = authenticationContext")
                args.add(parameterName)
                if (index < function.parameters.size - 1) append(", ")
                return@forEachIndexed
            }
            append("%L = %L")
            if (!resolved.isMarkedNullable) {
                append(" ?: error(\"%L is required\")")
                args.add(parameterName)
            }
            if (index < function.parameters.size - 1) append(", ")
            args.add(parameterName)
            args.add(parameterName)
        }
        append(")\n")
        append("result\n")
    }

    internal fun getBatchType(function: KSFunctionDeclaration): KSValueParameter? {
        for (parameter in function.parameters) {
            val parameterType = parameter.type.resolve()
            val classType = parameterType.declaration
            if (classType is KSClassDeclaration && classType.qualifiedName?.asString() == Types.Batch.canonicalName) {
                return parameter
            }
        }
        return null
    }

    internal fun StringBuilder.appendSource(parameterName: String, type: TypeName, args: MutableList<Any>) {
        append("val %L = environment.sourceAs<%T>()\n")
        args.add(parameterName)
        args.add(type)
    }

    fun StringBuilder.appendArgument(type: TypeName, parameterName: String, args: MutableList<Any>) =
        Companion.appendArgument(this, type, parameterName, args)

    companion object {
        /**
         * Builds a serializer format string and collects the corresponding KotlinPoet args
         * for a given type, handling nested collections recursively.
         */
        internal fun serializerFor(type: TypeName): Pair<String, List<Any>> {
            if (type.isCollection) {
                val elementType = (type as ParameterizedTypeName).typeArguments.first().copy(nullable = false)
                val (innerFormat, innerArgs) = serializerFor(elementType)
                return "ListSerializer($innerFormat)" to innerArgs
            }
            if (type.isOffsetDateTime) {
                return "OffsetDateTimeSerializer()" to emptyList()
            }
            return "%T.serializer()" to listOf(type.copy(nullable = false))
        }

        /**
         * Appends the code fragment that deserializes a GraphQL argument of the given
         * [type] into the [StringBuilder] and pushes the matching KotlinPoet args
         * into [args].
         */
        internal fun appendArgument(sb: StringBuilder, type: TypeName, parameterName: String, args: MutableList<Any>) = with(sb) {
            if (type.isJsonElement) {
                append("val %L_map = environment.getArgument<Any>(%S)\n")
                append("val %L = anyToJsonElement(%L_map)\n")
                args.add(parameterName)
                args.add(parameterName)
                args.add(parameterName)
                args.add(parameterName)
            } else if (type.isPrimitive) {
                append("val %L = environment.getArgument<%T>(%S)\n")
                args.add(parameterName)
                args.add(type.copy(nullable = false))
                args.add(parameterName)
            } else {
                append("val %LMap = environment.getArgument<Any>(%S)\n")
                append("val %LjsonElement = anyToJsonElement(%LMap)\n")
                val (serializerFormat, serializerArgs) = serializerFor(type.copy(nullable = false))
                if (type.isNullable) {
                    append("val %L = if (%LjsonElement is JsonNull) null else json.decodeFromJsonElement($serializerFormat, %LjsonElement)\n")
                } else {
                    append("val %L = json.decodeFromJsonElement($serializerFormat, %LjsonElement)\n")
                }
                args.add(parameterName) // %L in "val %LMap = ..."
                args.add(parameterName) // %S in "...getArgument<Any>(%S)..."
                args.add(parameterName) // %L in "val %LjsonElement = ..."
                args.add(parameterName) // %L in "...anyToJsonElement(%LMap)..."
                args.add(parameterName) // %L in "val %L = ..."
                if (type.isNullable) {
                    args.add(parameterName) // %L in "if (%LjsonElement is JsonNull)..."
                }
                args.addAll(serializerArgs)
                args.add(parameterName) // %L in "...%LjsonElement)\n"
            }
        }
    }

    internal fun StringBuilder.appendBatchFunction(function: KSFunctionDeclaration, sourceParameterName: String, sourceParameterType: KSType, args: MutableList<Any>, batchType: KSType) {
        val batchKeyTypeName = batchType.arguments[0].type?.toTypeName() ?: error("missing type")
        val batchReturnTypeName = batchType.arguments[1].type?.toTypeName() ?: error("missing type")

        append("val call = environment.context.getAs<ServerCall>(\"call\") ?: error(\"Missing call\")\n")

        append("val %L = environment.sourceAs<%T>()\n")
        args.add(sourceParameterName)
        args.add(sourceParameterType.toTypeName())

        append(
            """
            |val dataLoader = environment.dataLoaderRegistry.getOrPutLoader<%T, %T>(%S, environment.arguments) { keys, keyContexts ->
            |    bosca.db.withConnectionManager {
            |    val batchEnvironment = BatchLoaderEnvironment(keyContexts)
            |        val batch = Batch<%T, %T>(keys)
            |        
        """.trimMargin()
        )

        args.add(batchKeyTypeName)
        args.add(batchReturnTypeName)
        args.add("${function.parentDeclaration!!.qualifiedName?.asString()}${function.simpleName.asString()}")
        args.add(batchKeyTypeName)
        args.add(batchReturnTypeName)
        append("\n")
        append("       controller.%L(")
        for (parameter in function.parameters) {
            val typeName = parameter.type.resolve().toTypeName().let {
                if (it is ParameterizedTypeName) it.rawType else it as ClassName
            }
            when (typeName.canonicalName) {
                ResolverContext::class.qualifiedName, "bosca.graphql.DataFetchingEnvironment" -> {
                    append("environment, ")
                }
                Types.ServerCall.canonicalName -> {
                    append("call, ")
                }
                Types.AuthenticationContext.canonicalName -> {
                    append("authenticationContext, ")
                }
                Types.BatchLoaderEnvironment.canonicalName -> {
                    append("batchEnvironment, ")
                }
                else -> {
                    append("batch, ")
                }
            }
        }
        append(")\n")
        args.add(function.simpleName.asString())

        append(
            """
            |        batch.getResults()
            |    }
            |}
            |
            """.trimMargin()
        )

        val batchKeyAnnotation = sourceParameterType.declaration.annotations.firstOrNull { it.shortName.asString() == Types.BatchKey.simpleName } ?: error("missing BatchType for ${sourceParameterType.declaration.qualifiedName?.asString()}")
        val batchKeyProperty = batchKeyAnnotation.arguments.first { it.name?.asString() == "property" }.value.toString()
        if (batchKeyProperty.isNotEmpty()) {
            append("dataLoader.load(%L?.%L ?: error(\"missing source\"), BatchContext(environment.arguments, %L))\n")
            args.add(sourceParameterName)
            args.add(batchKeyProperty)
            args.add(sourceParameterName)
        } else {
            val batchKeyType = batchKeyAnnotation.arguments.firstOrNull { it.name?.asString() == "type" }?.value as KSType
            append("dataLoader.load(%T(%L ?: error(\"missing source\")), BatchContext(environment.arguments, %L))\n")
            args.add(batchKeyType.toTypeName())
            args.add(sourceParameterName)
            args.add(sourceParameterName)
        }
    }

}
