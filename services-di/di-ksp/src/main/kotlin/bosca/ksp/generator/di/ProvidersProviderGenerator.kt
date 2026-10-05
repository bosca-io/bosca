package bosca.ksp.generator.di

import bosca.di.annotation.Generated
import bosca.di.annotation.Provider
import bosca.di.annotation.Provides
import bosca.ksp.Types
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundProvider
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
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
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import kotlin.reflect.KClass

class ProvidersProviderGenerator(
    codeGenerator: CodeGenerator,
    private val onProvider: (provider: FoundProvider) -> Unit,
) : AbstractGenerator<FoundProvider>(codeGenerator) {

    override fun generate(items: Collection<FoundProvider>) {
        items.forEach { item ->
            if (item.generatesProviderFunctions) {
                generateProviderFunctions(item)
            } else {
                generateClassProvider(item)
            }
        }
    }

    private fun generateClassProvider(item: FoundProvider) {
        FileSpec.builder(item.provider)
            .addAnnotation(fileSuppressions())
            .addImport(Types.ProviderRegistry, "")
            .addType(
                TypeSpec.classBuilder(item.provider)
                    .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                    .addAnnotation(providerAnnotation(item.singleton, item.name))
                    .addAnnotation(providesAnnotation(item.provides, item.name))
                    .addSuperinterface(Types.ObjectProvider.parameterizedBy(item.provides))
                    .addProperty(typeProperty(item.provides))
                    .addFunction(
                        FunSpec.builder("get")
                            .addCode(
                                "return %T(${item.classDeclaration.primaryConstructor?.parameters?.dependencies().orEmpty()})",
                                item.provides,
                            )
                            .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                            .returns(item.provides)
                            .build()
                    )
                    .build()
            )
            .build()
            .writeTo(codeGenerator, true)
    }

    private fun generateProviderFunctions(item: FoundProvider) {
        val functions = item.classDeclaration.getAllFunctions()
            .filter { function ->
                function.annotations.any { it.shortName.asString() == Provider::class.simpleName }
            }
            .toList()
        if (functions.isEmpty()) return

        val sourceNames = functions.map { it.providerAnnotation().stringArgument("name") }.distinct()
        val sourceProvider = TypeSpec.classBuilder(item.provider)
            .addAnnotation(AnnotationSpec.builder(Generated::class).build())
            .addAnnotation(providerAnnotation(singleton = true, name = ""))
            .addAnnotations(sourceNames.map { providesAnnotation(item.provides, it) })
            .addSuperinterface(Types.ObjectProvider.parameterizedBy(item.provides))
            .addProperty(typeProperty(item.provides))
            .addFunction(
                FunSpec.builder("get")
                    .addCode(
                        "return %T(${item.classDeclaration.primaryConstructor?.parameters?.dependencies().orEmpty()})",
                        item.provides,
                    )
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .returns(item.provides)
                    .build()
            )
            .build()

        sourceNames.forEach { name ->
            onProvider(
                item.copy(
                    singleton = true,
                    name = name,
                    generatesProviderFunctions = false,
                )
            )
        }

        val functionProviders = functions.map { function ->
            generateFunctionProvider(item, function)
        }

        FileSpec.builder(item.provider)
            .addAnnotation(fileSuppressions())
            .addImport(Types.ProviderRegistry, "")
            .addType(sourceProvider)
            .addTypes(functionProviders)
            .build()
            .writeTo(codeGenerator, true)
    }

    private fun generateFunctionProvider(item: FoundProvider, function: KSFunctionDeclaration): TypeSpec {
        val providerAnnotation = function.providerAnnotation()
        val singleton = providerAnnotation.booleanArgument("singleton")
        val name = providerAnnotation.stringArgument("name")
        val functionName = function.simpleName.asString()
        val provider = ClassName(
            item.provider.packageName,
            "${item.classDeclaration.simpleName.asString()}${functionName}Provider${functionName}FunctionProvider",
        )
        val returnType = function.returnType?.resolve()?.toTypeName() ?: error("missing return type")

        onProvider(
            FoundProvider(
                provider = provider,
                provides = returnType,
                singleton = singleton,
                name = name,
                classDeclaration = item.classDeclaration,
            )
        )

        return TypeSpec.classBuilder(provider)
            .addAnnotation(AnnotationSpec.builder(Generated::class).build())
            .addAnnotation(providerAnnotation(singleton, name))
            .addAnnotation(providesAnnotation(returnType, name))
            .addSuperinterface(Types.ObjectProvider.parameterizedBy(returnType))
            .addProperty(typeProperty(returnType))
            .addFunction(
                FunSpec.builder("get")
                    .also { builder ->
                        if (name.isEmpty()) {
                            builder.addCode("val provider = ProviderRegistry.get<%T>()\n", item.provides)
                        } else {
                            builder.addCode("val provider = ProviderRegistry.get<%T>(%S)\n", item.provides, name)
                        }
                    }
                    .addCode("return provider.%L(${function.parameters.dependencies()})", functionName)
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .returns(returnType)
                    .build()
            )
            .build()
    }

    private fun typeProperty(type: TypeName): PropertySpec = if (type is ParameterizedTypeName) {
        PropertySpec.builder("type", KClass::class.asClassName().parameterizedBy(type), KModifier.OVERRIDE)
            .initializer("%T::class as KClass<%T>", type.rawType, type)
            .build()
    } else {
        PropertySpec.builder("type", KClass::class.asClassName().parameterizedBy(type), KModifier.OVERRIDE)
            .initializer("%T::class", type)
            .build()
    }

    private fun providerAnnotation(singleton: Boolean, name: String): AnnotationSpec =
        AnnotationSpec.builder(Provider::class)
            .addMember("singleton = %L, name = %S", singleton, name)
            .build()

    private fun providesAnnotation(type: TypeName, name: String): AnnotationSpec =
        AnnotationSpec.builder(Provides::class)
            .addMember("type = %T::class, name = %S", type.rawType(), name)
            .build()

    private fun fileSuppressions(): AnnotationSpec = AnnotationSpec.builder(Suppress::class)
        .addMember("\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"USELESS_ELVIS\", \"OPT_IN_USAGE\", \"USELESS_CAST\"")
        .build()
}

private fun KSFunctionDeclaration.providerAnnotation(): KSAnnotation =
    annotations.first { it.shortName.asString() == Provider::class.simpleName }

private fun KSAnnotation.booleanArgument(name: String): Boolean =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? Boolean ?: false

private fun KSAnnotation.stringArgument(name: String): String =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? String ?: ""

private fun Iterable<KSValueParameter>.dependencies(): String = joinToString { parameter ->
    val providerName = parameter.annotations
        .firstOrNull { it.shortName.asString() == "ProviderName" }
        ?.arguments
        ?.firstOrNull()
        ?.value as? String
    val type = parameter.type.toTypeName()
    if (providerName != null) {
        if (type is ParameterizedTypeName && type.rawType == Types.ObjectProvider) {
            "ProviderRegistry.getProvider(\"$providerName\")"
        } else {
            "ProviderRegistry.get(\"$providerName\")"
        }
    } else if (type is ParameterizedTypeName && type.rawType == Types.ObjectProvider) {
        "ProviderRegistry.getProvider()"
    } else {
        "ProviderRegistry.get()"
    }
}

private fun TypeName.rawType(): ClassName = when (this) {
    is ClassName -> this
    is ParameterizedTypeName -> rawType
    else -> error("Unsupported provider type: $this")
}
