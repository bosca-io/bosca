package bosca.ksp.visitors

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.toTypeName

data class FoundCacheSerializer(
    val key: TypeName,
    val type: String,
    val serializer: TypeName,
    val classDeclaration: KSClassDeclaration
)

class CacheSerializerVisitor(private val processed: MutableSet<Pair<String, String>>) : KSVisitorVoid(), VisitorConsumer<FoundCacheSerializer> {

    private val types = mutableSetOf<FoundCacheSerializer>()

    override fun consume() = types.toList().also { types.clear() }

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val packageName = classDeclaration.containingFile!!.packageName.asString()
        val currentClassName = classDeclaration.simpleName.asString()

        val processing = Pair(packageName, currentClassName)
        if (processed.contains(processing)) return
        processed.add(processing)

        val keyType = classDeclaration.superTypes.find {
            it.resolve().declaration.simpleName.asString() == "CacheKeySerializer"
        }?.resolve()?.arguments?.firstOrNull()?.toTypeName() ?: error("CacheKeySerializer not found")

        types.add(
            FoundCacheSerializer(
                keyType,
                classDeclaration.annotations.first { it.shortName.asString() == "Serializer" }.arguments.first { it.name?.asString() == "type" }.value as String,
                ClassName(packageName, currentClassName),
                classDeclaration
            )
        )
    }
}