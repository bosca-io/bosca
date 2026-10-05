package bosca.ksp.visitors

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.di.annotation.Provides
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.toTypeName

data class ProviderBindingKey(val type: ClassName, val name: String)

data class FoundProvider(
    val provider: ClassName,
    val provides: TypeName,
    val singleton: Boolean,
    val name: String,
    val classDeclaration: KSClassDeclaration,
    val generatesProviderFunctions: Boolean = false,
) {
    val bindingKey: ProviderBindingKey
        get() = ProviderBindingKey(provides.rawType(), name)

    override fun equals(other: Any?): Boolean {
        if (other !is FoundProvider) return false
        return provider == other.provider && bindingKey == other.bindingKey
    }

    override fun hashCode(): Int = 31 * provider.hashCode() + bindingKey.hashCode()
}

class ProviderVisitor(
    private val processed: MutableSet<Pair<String, String>>,
    private val logger: KSPLogger,
    private val allProviders: MutableSet<FoundProvider>
) : KSVisitorVoid(), VisitorConsumer<FoundProvider> {

    private val providers = mutableSetOf<FoundProvider>()

    override fun consume(): List<FoundProvider> = providers.toList().also { providers.clear() }

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val packageName = classDeclaration.packageName.asString()
        val currentClassName = classDeclaration.simpleName.asString()
        val declarationKey = Pair(packageName, currentClassName)

        if (!processed.add(declarationKey)) return

        val providerAnnotation = classDeclaration.annotation(Provider::class.simpleName)
        if (providerAnnotation != null) {
            visitProviderClass(classDeclaration, providerAnnotation, packageName, currentClassName)
            return
        }

        if (classDeclaration.annotation(Providers::class.simpleName) != null) {
            val providerFunctions = classDeclaration.providerFunctions()
            if (providerFunctions.isNotEmpty()) {
                providers.add(
                    FoundProvider(
                        provider = ClassName(packageName, "${currentClassName}Provider"),
                        provides = ClassName(packageName, currentClassName),
                        singleton = true,
                        name = "",
                        classDeclaration = classDeclaration,
                        generatesProviderFunctions = true,
                    )
                )
            }
        }
        logger.info("provider : ${providers.size}")
    }

    private fun visitProviderClass(
        classDeclaration: KSClassDeclaration,
        providerAnnotation: KSAnnotation,
        packageName: String,
        currentClassName: String,
    ) {
        val singleton = providerAnnotation.booleanArgument("singleton")
        val name = providerAnnotation.stringArgument("name")
        val objectProviderType = classDeclaration.superTypes
            .map { it.resolve() }
            .firstOrNull { it.declaration.qualifiedName?.asString() == "bosca.di.ObjectProvider" }

        if (objectProviderType == null) {
            val provider = FoundProvider(
                provider = ClassName(packageName, "${currentClassName}Provider"),
                provides = ClassName(packageName, currentClassName),
                singleton = singleton,
                name = name,
                classDeclaration = classDeclaration,
            )
            providers.add(provider)
            allProviders.add(provider)
            return
        }

        val providedType = objectProviderType.arguments.first().type?.resolve()?.toTypeName()
            ?: error("missing ObjectProvider type for ${classDeclaration.qualifiedName?.asString()}")
        val providesAnnotations = classDeclaration.annotations
            .filter { it.shortName.asString() == Provides::class.simpleName }
            .toList()

        if (providesAnnotations.isEmpty()) {
            allProviders.add(
                FoundProvider(
                    provider = ClassName(packageName, currentClassName),
                    provides = providedType,
                    singleton = singleton,
                    name = name,
                    classDeclaration = classDeclaration,
                )
            )
            return
        }

        providesAnnotations.forEach { providesAnnotation ->
            val annotatedType = providesAnnotation.arguments
                .firstOrNull { it.name?.asString() == "type" }
                ?.value as? KSType
                ?: error("missing Provides type for ${classDeclaration.qualifiedName?.asString()}")
            val annotatedTypeName = annotatedType.toTypeName().rawType()
            check(annotatedTypeName == providedType.rawType()) {
                "${classDeclaration.qualifiedName?.asString()} is an ObjectProvider<$providedType> " +
                    "but @Provides declares $annotatedTypeName"
            }
            allProviders.add(
                FoundProvider(
                    provider = ClassName(packageName, currentClassName),
                    provides = providedType,
                    singleton = singleton,
                    name = providesAnnotation.stringArgument("name"),
                    classDeclaration = classDeclaration,
                )
            )
        }
    }
}

private fun KSClassDeclaration.annotation(name: String?): KSAnnotation? =
    annotations.firstOrNull { it.shortName.asString() == name }

private fun KSClassDeclaration.providerFunctions() = getAllFunctions().filter { function ->
    function.annotations.any { it.shortName.asString() == Provider::class.simpleName }
}.toList()

private fun KSAnnotation.booleanArgument(name: String): Boolean =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? Boolean ?: false

private fun KSAnnotation.stringArgument(name: String): String =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? String ?: ""

private fun TypeName.rawType(): ClassName = when (this) {
    is ClassName -> this
    is ParameterizedTypeName -> rawType
    else -> error("Unsupported provider type: $this")
}
