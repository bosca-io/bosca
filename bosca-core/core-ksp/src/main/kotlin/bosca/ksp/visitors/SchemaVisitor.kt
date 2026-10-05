package bosca.ksp.visitors

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.squareup.kotlinpoet.ClassName

data class FoundSchema(val classDeclaration: KSClassDeclaration, val schema: ClassName, val resources: List<FoundResource>)

data class FoundResource(val name: String, val resource: String)

class SchemaVisitor(
    private val processed: MutableSet<Pair<String, String>>,
    private val logger: KSPLogger,
) : KSVisitorVoid(), VisitorConsumer<FoundSchema> {

    private val schemas = mutableSetOf<FoundSchema>()

    override fun consume(): List<FoundSchema> = schemas.toList().also { schemas.clear() }

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val packageName = classDeclaration.containingFile!!.packageName.asString()
        val currentClassName = classDeclaration.simpleName.asString()
        val dispatcherPair = Pair(packageName, currentClassName)

        if (processed.contains(dispatcherPair)) return
        processed.add(dispatcherPair)

        if (!classDeclaration.annotations.any { it.shortName.asString() == Schemas::class.simpleName }) {
            return
        }

        val resources = mutableListOf<FoundResource>()
        classDeclaration
            .getAllProperties()
            .filter { it.annotations.any { it.shortName.asString() == Schema::class.simpleName } }
            .forEach {
                val resource = it.annotations.first { it.shortName.asString() == Schema::class.simpleName }.arguments.first().value.toString()
                resources.add(FoundResource(it.simpleName.asString(), resource))
            }

        val provider = FoundSchema(classDeclaration, ClassName(packageName, currentClassName), resources)
        schemas.add(provider)

        logger.info("schemas : ${schemas.size}")
    }
}