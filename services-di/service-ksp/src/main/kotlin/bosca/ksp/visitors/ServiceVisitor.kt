package bosca.ksp.visitors

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.toTypeName

data class FoundService(
    val service: TypeName,
    val implementation: ClassName,
    val provider: ClassName,
    val classDeclaration: KSClassDeclaration
)

class ServiceVisitor(private val processed: MutableSet<Pair<String, String>>, private val logger: KSPLogger) : KSVisitorVoid(), VisitorConsumer<FoundService> {

    private val types = mutableSetOf<FoundService>()

    override fun consume() = types.toList().also { types.clear() }

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val packageName = classDeclaration.containingFile!!.packageName.asString()
        val currentClassName = classDeclaration.simpleName.asString()

        val processing = Pair(packageName, currentClassName)
        if (processed.contains(processing)) return
        processed.add(processing)

        classDeclaration.superTypes.forEach {
            val superType = it.resolve().declaration as KSClassDeclaration
            val service = superType.getAllSuperTypes().firstOrNull { it.declaration.simpleName.asString() == "Service" }
            if (service != null) {
                val provider = ClassName(packageName, "${currentClassName}Provider")
                types.add(
                    FoundService(
                        it.toTypeName(),
                        ClassName(packageName, currentClassName),
                        provider,
                        classDeclaration
                    )
                )
            } else {
                logger.warn("Service : ${classDeclaration.simpleName.asString()} is missing a Service super type")
            }
        }
    }
}