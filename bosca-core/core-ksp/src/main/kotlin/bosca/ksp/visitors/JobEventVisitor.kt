package bosca.ksp.visitors

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName

data class FoundJobEvent(
    val event: TypeName,
    val jobs: List<KSClassDeclaration>,
    val pubsubChannel: String,
    val displayName: String,
    val description: String,
    val classDeclaration: KSClassDeclaration
)

class JobEventVisitor(private val processed: MutableSet<Pair<String, String>>, private val logger: KSPLogger) : KSVisitorVoid(), VisitorConsumer<FoundJobEvent> {

    private val types = mutableSetOf<FoundJobEvent>()

    override fun consume() = types.toList().also { types.clear() }

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val packageName = classDeclaration.containingFile!!.packageName.asString()
        val currentClassName = classDeclaration.simpleName.asString()

        val processing = Pair(packageName, currentClassName)
        if (processed.contains(processing)) return
        processed.add(processing)

        val annotation = classDeclaration.annotations.first { it.shortName.asString() == "JobEvent" }
        @Suppress("UNCHECKED_CAST")
        val jobs = annotation.arguments.first { it.name?.asString() == "jobs" }.value as List<KSType>
        val pubsubChannel = annotation.arguments.first { it.name?.asString() == "pubsubChannel" }.value as String
        val displayName = annotation.arguments.firstOrNull { it.name?.asString() == "displayName" }?.value as? String ?: ""
        val description = annotation.arguments.firstOrNull { it.name?.asString() == "description" }?.value as? String ?: ""

        logger.info("JobEvent : $currentClassName")
        types.add(
            FoundJobEvent(
                ClassName(packageName, currentClassName),
                jobs.map { it.declaration as KSClassDeclaration },
                pubsubChannel,
                displayName,
                description,
                classDeclaration
            )
        )
    }
}