package bosca.ksp.visitors

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.squareup.kotlinpoet.ClassName

data class FoundPageController(
    val page: ClassName,
    val pageProvider: ClassName,
    val classDeclaration: KSClassDeclaration
)

class PageControllerVisitor(private val processed: MutableSet<Pair<String, String>>) : KSVisitorVoid(), VisitorConsumer<FoundPageController> {

    private val types = mutableSetOf<FoundPageController>()

    override fun consume() = types.toList().also { types.clear() }

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val packageName = classDeclaration.containingFile?.packageName?.asString() ?: error("No package found")
        val className = classDeclaration.simpleName.asString()

        val processing = Pair(packageName, className)
        if (processed.contains(processing)) return
        processed.add(processing)

        val pageProviderClassName = ClassName(packageName, "${className}Provider")

        types.add(
            FoundPageController(
                page = ClassName(packageName, className),
                pageProvider = pageProviderClassName,
                classDeclaration = classDeclaration
            )
        )
    }
}