package bosca.ksp.visitors

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.squareup.kotlinpoet.ClassName

data class FoundSchedulable(
    val className: ClassName,
    val name: String,
    val description: String,
    val classDeclaration: KSClassDeclaration
)

class SchedulableVisitor(private val processed: MutableSet<Pair<String, String>>, private val logger: KSPLogger) : KSVisitorVoid(), VisitorConsumer<FoundSchedulable> {

    private val types = mutableSetOf<FoundSchedulable>()

    override fun consume() = types.toList().also { types.clear() }

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val packageName = classDeclaration.containingFile!!.packageName.asString()
        val currentClassName = classDeclaration.simpleName.asString()

        val processing = Pair(packageName, currentClassName)
        if (processed.contains(processing)) return
        processed.add(processing)

        val annotation = classDeclaration.annotations.first { it.shortName.asString() == "Schedulable" }
        val name = annotation.arguments.first { it.name?.asString() == "name" }.value as String
        val description = annotation.arguments.first { it.name?.asString() == "description" }.value as String

        logger.info("Schedulable : $currentClassName")
        types.add(
            FoundSchedulable(
                ClassName(packageName, currentClassName),
                name,
                description,
                classDeclaration
            )
        )
    }
}
