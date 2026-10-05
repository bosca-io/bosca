package bosca.ksp.visitors

import bosca.graphql.annotations.TypeController
import bosca.ksp.Types
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName

data class FoundTypeController(
    val targetClass: KSType,
    val typeName: String,
    val controller: ClassName,
    val controllerProvider: ClassName,
    val dispatcher: TypeName,
    val dispatcherProvider: ClassName,
    val classDeclaration: KSClassDeclaration
)

class TypeControllerVisitor(private val processed: MutableSet<Pair<String, String>>) : KSVisitorVoid(), VisitorConsumer<FoundTypeController> {

    private val types = mutableSetOf<FoundTypeController>()

    override fun consume() = types.toList().also { types.clear() }

    private fun findTargetClass(classDeclaration: KSClassDeclaration): KSType {
        val c = classDeclaration.superTypes.firstOrNull {
            val type = it.resolve().toTypeName()
            type is ParameterizedTypeName && type.rawType.canonicalName == Types.GraphQLController.canonicalName
        }?.resolve() ?: error("No type interface found for $classDeclaration")
        return c.arguments.first().type?.resolve() ?: error("No target class found")
    }

    private fun findTypeName(classDeclaration: KSClassDeclaration, targetClass: ClassName): String {
        val typeControllerName = TypeController::class.simpleName ?: error("TypeController class not found")
        val typeAnnotation = classDeclaration.annotations.first { it.shortName.asString() == typeControllerName }
        return typeAnnotation
            .arguments
            .first { it.name?.asString() == "type" }
            .value
            ?.toString()
            ?.takeIf { it.isNotEmpty() }
            ?: targetClass.simpleName
    }

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val packageName = classDeclaration.containingFile?.packageName?.asString() ?: error("No package found")
        val className = classDeclaration.simpleName.asString()
        val targetClassName = findTargetClass(classDeclaration)

        val processing = Pair(packageName, className)
        if (processed.contains(processing)) return
        processed.add(processing)

        val typeName = findTypeName(classDeclaration, targetClassName.toClassName())
        val dispatcherClassName = ClassName(packageName, "${className}Dispatcher")
        val dispatcherProviderClassName = ClassName(packageName, "${className}DispatcherProvider")
        val controllerProviderClassName = ClassName(packageName, "${className}Provider")

        types.add(
            FoundTypeController(
                targetClassName,
                typeName,
                ClassName(packageName, className),
                controllerProviderClassName,
                dispatcherClassName,
                dispatcherProviderClassName,
                classDeclaration
            )
        )
    }
}