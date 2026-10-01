package bosca.ksp.visitors

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName

data class FoundJobDefinition(
    val job: TypeName,
    val provider: ClassName,
    val executor: ClassName,
    val definition: KSClassDeclaration,
    val queueName: String,
    val JobConfigurationEnqueuer: ClassName,
    val jobName: String,
    val classDeclaration: KSClassDeclaration,
    val displayName: String
)

class JobDefinitionVisitor(private val processed: MutableSet<Pair<String, String>>) : KSVisitorVoid(), VisitorConsumer<FoundJobDefinition> {

    private val types = mutableSetOf<FoundJobDefinition>()

    override fun consume() = types.toList().also { types.clear() }

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val packageName = classDeclaration.containingFile!!.packageName.asString()
        val currentClassName = classDeclaration.simpleName.asString()

        val processing = Pair(packageName, currentClassName)
        if (processed.contains(processing)) return
        processed.add(processing)

        val definition = classDeclaration.annotations.first { it.shortName.asString() == "JobDefinition" }.arguments

        val jobName = definition.firstOrNull { it.name?.asString() == "name" }?.value as? String ?: currentClassName
        val rawDisplay = definition.firstOrNull { it.name?.asString() == "displayName" }?.value as? String
        val resolvedDisplay = rawDisplay?.takeIf { it.isNotBlank() } ?: jobName

        types.add(
            FoundJobDefinition(
                ClassName(packageName, currentClassName),
                ClassName(packageName, "${currentClassName}Provider"),
                ClassName(packageName, "${currentClassName}Executor"),
                (definition.first { it.name?.asString() == "definition" }.value as KSType).declaration as KSClassDeclaration,
                definition.firstOrNull { it.name?.asString() == "queue" }?.value as? String ?: "",
                ClassName(packageName, "${currentClassName}ConfigurationEnqueuer"),
                jobName,
                classDeclaration,
                resolvedDisplay,
            )
        )
    }
}