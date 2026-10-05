package bosca.ksp.visitors

import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName

data class FoundSerializableClass(
    val className: ClassName,
    val qualifiedName: String,
    val classDeclaration: KSClassDeclaration,
    /** Eagerly captured while the KSP symbol is valid; generators run later during `finish()`. */
    val assignableTypes: List<ClassName>,
)

class SerializableClassVisitor(
    private val processed: MutableSet<Pair<String, String>>
) : KSVisitorVoid(), VisitorConsumer<FoundSerializableClass> {

    private val types = mutableSetOf<FoundSerializableClass>()

    override fun consume() = types.toList().also { types.clear() }

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        if (classDeclaration.classKind == ClassKind.ANNOTATION_CLASS) return

        val packageName = classDeclaration.containingFile?.packageName?.asString() ?: return

        // `X.serializer()` of a generic @Serializable type needs per-type-argument serializers, so it
        // can't be cached/registered this way — skip it (the same constraint already excludes generic
        // top-level types, none of which exist today).
        if (classDeclaration.typeParameters.isNotEmpty()) return

        // Walk the enclosing-class chain so a *nested* @Serializable type — e.g. a Koog tool's
        // `Input`/`Output` — is registered too, not just top-level ones. kotlinx.serialization's
        // reflective serializer lookup needs native-image reflection metadata for these; skipping them
        // is what made `CreateDocumentTool$Input` an "Unresolved class" under native image. Every
        // enclosing declaration must be a public class, otherwise the type can't be referenced as
        // `Outer.Inner` from the generated registrar nor resolved via `Class.forName`.
        val simpleNames = ArrayDeque<String>()
        var declaration: KSDeclaration? = classDeclaration
        while (declaration is KSClassDeclaration) {
            if (!declaration.isPublic()) return
            simpleNames.addFirst(declaration.simpleName.asString())
            declaration = declaration.parentDeclaration
        }
        // A non-null remaining parent means a function-local / anonymous class we can't address by name.
        if (declaration != null) return

        // Dedup by the dotted nesting path so two tools in one package (each with an `Input`) don't
        // collide; a dotted key can never clash with a top-level simple name (those have no dots).
        if (!processed.add(packageName to simpleNames.joinToString("."))) return

        // Binary name (`Outer$Inner`) so BoscaFeature's `Class.forName` resolves it.
        val binaryName = simpleNames.joinToString("$")
        types.add(
            FoundSerializableClass(
                // Nested ClassName → renders as `Outer.Inner` (valid `::class.java` / `.serializer()`).
                ClassName(packageName, simpleNames.toList()),
                if (packageName.isEmpty()) binaryName else "$packageName.$binaryName",
                classDeclaration,
                classDeclaration.collectDomainSupertypes(),
            )
        )
    }

    /**
     * Captures transitive domain supertypes before KSP advances rounds and invalidates type symbols.
     * Platform collection types are implementation detail rather than useful pipeline cast targets.
     */
    private fun KSClassDeclaration.collectDomainSupertypes(): List<ClassName> {
        val types = linkedMapOf<String, ClassName>()

        fun collect(declaration: KSClassDeclaration) {
            for (reference in declaration.superTypes) {
                val superDeclaration = reference.resolve().declaration as? KSClassDeclaration ?: continue
                val name = superDeclaration.qualifiedName?.asString() ?: continue
                if (
                    name == "kotlin.Any" ||
                    name.startsWith("java.") ||
                    name.startsWith("javax.") ||
                    name.startsWith("kotlin.") ||
                    name.startsWith("kotlinx.")
                ) continue
                if (types.putIfAbsent(name, superDeclaration.toClassName()) == null) collect(superDeclaration)
            }
        }

        collect(this)
        return types.values.toList()
    }
}
