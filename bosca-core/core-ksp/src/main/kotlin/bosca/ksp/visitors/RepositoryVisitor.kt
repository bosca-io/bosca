package bosca.ksp.visitors

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName

data class FoundRepository(
    val repository: TypeName,
    val provider: ClassName,
    val providers: ClassName,
    val classDeclaration: KSClassDeclaration
)

class RepositoryVisitor(private val processed: MutableSet<Pair<String, String>>) : KSVisitorVoid(), VisitorConsumer<FoundRepository> {

    private val types = mutableSetOf<FoundRepository>()

    override fun consume() = types.toList().also { types.clear() }

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val packageName = classDeclaration.containingFile!!.packageName.asString()
        val currentClassName = classDeclaration.simpleName.asString()

        val processing = Pair(packageName, currentClassName)
        if (processed.contains(processing)) return
        processed.add(processing)

        types.add(
            FoundRepository(
                ClassName(packageName, currentClassName),
                ClassName(packageName, "${currentClassName}Provider"),
                ClassName(packageName, "${currentClassName}Impl"),
                classDeclaration
            )
        )
    }
}