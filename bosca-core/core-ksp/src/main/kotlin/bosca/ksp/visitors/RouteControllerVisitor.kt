package bosca.ksp.visitors

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.squareup.kotlinpoet.ClassName

data class FoundRouteController(
    val route: ClassName,
    val routeProvider: ClassName,
    val classDeclaration: KSClassDeclaration
)

class RouteControllerVisitor(private val processed: MutableSet<Pair<String, String>>) : KSVisitorVoid(), VisitorConsumer<FoundRouteController> {

    private val types = mutableSetOf<FoundRouteController>()

    override fun consume() = types.toList().also { types.clear() }

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val packageName = classDeclaration.containingFile?.packageName?.asString() ?: error("No package found")
        val className = classDeclaration.simpleName.asString()

        val processing = Pair(packageName, className)
        if (processed.contains(processing)) return
        processed.add(processing)

        val pageProviderClassName = ClassName(packageName, "${className}Provider")

        types.add(
            FoundRouteController(
                route = ClassName(packageName, className),
                routeProvider = pageProviderClassName,
                classDeclaration = classDeclaration
            )
        )
    }
}