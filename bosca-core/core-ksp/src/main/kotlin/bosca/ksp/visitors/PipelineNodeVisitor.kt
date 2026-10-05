package bosca.ksp.visitors

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName

/** One declared `@InputSlot` on a node. [kind] and the node's enum args are the enum *entry* names. */
data class FoundPipelineSlot(
    val name: String,
    val kind: String,
    val typeLabel: String,
    val description: String,
    /** The specific object type the slot requires, or null = any; emitted as `<type>.serializer().descriptor.serialName`. */
    val type: ClassName?,
    val schema: String,
    val required: Boolean,
)

/** One declared `@OutputSlot` on a node. [kind] is the enum *entry* name. */
data class FoundPipelineOutput(
    val name: String,
    val kind: String,
    val error: Boolean,
    /** The specific object type the port emits, or null = any; emitted as `<type>.serializer().descriptor.serialName`. */
    val type: ClassName?,
    val typeLabel: String,
    val description: String,
)

/** One declared `@SettingOption` (ENUM choice or literal REFERENCE extra). */
data class FoundPipelineOption(
    val value: String,
    val label: String,
)

/**
 * One declared `@SettingSlot` (or nested `@SettingField`) on a node. [control] is the enum *entry*
 * name; [reference] is the enum entry name or null when `NONE`/absent. A `@SettingField` simply has no
 * [fields]/[itemLabel]/[group]/[visibleWhen*], which decode to empty here.
 */
data class FoundPipelineSetting(
    val name: String,
    val control: String,
    val label: String,
    val description: String,
    val placeholder: String,
    val default: String,
    val required: Boolean,
    val secret: Boolean,
    val mono: Boolean,
    val language: String,
    val reference: String?,
    val options: List<FoundPipelineOption>,
    val fields: List<FoundPipelineSetting>,
    val itemLabel: String,
    val group: String,
    val visibleWhenSetting: String,
    val visibleWhenEquals: String,
)

data class FoundPipelineNode(
    val node: TypeName,
    val serialName: String,
    val category: String,
    val label: String,
    val description: String,
    /** Organizational group/subgroup for the palette and node browser; `""` = unannotated. */
    val group: String = "",
    val subgroup: String = "",
    val inputs: List<FoundPipelineSlot>,
    val outputs: List<FoundPipelineOutput>,
    val settings: List<FoundPipelineSetting>,
    val classDeclaration: KSClassDeclaration,
)

/**
 * Collects every `@PipelineNodeType`-annotated node class so the per-module
 * `PipelineNodeSerializers` provider can register their explicit serializers and palette
 * descriptors. Mirrors [JobEventVisitor]. The node-type key is the class's `@SerialName` (the
 * polymorphic discriminator), falling back to the simple class name.
 *
 * `category`/`outputKind`/each slot's `kind` are enum arguments — KSP surfaces an enum value as the
 * entry's [KSClassDeclaration], so we read its `simpleName` (e.g. `TRANSFORM`, `UUID`) and let the
 * generator emit `NodeCategory.TRANSFORM` / `SlotKind.UUID` directly (no string mapping).
 */
class PipelineNodeVisitor(
    private val processed: MutableSet<Pair<String, String>>,
    private val logger: KSPLogger,
) : KSVisitorVoid(), VisitorConsumer<FoundPipelineNode> {

    private companion object {
        /**
         * The stored node's own flat-JSON keys: `type` is the polymorphic discriminator, `id` and
         * `position` the node envelope. Settings share that flat object, so a setting with one of
         * these names would overwrite the node's own key on save (the authoring surface spreads
         * settings into the stored node) — most fatally the discriminator, which makes the whole
         * pipeline undeserializable. Rejected at compile time.
         */
        val RESERVED_SETTING_NAMES = setOf("type", "id", "position")
    }

    private val types = mutableSetOf<FoundPipelineNode>()

    override fun consume() = types.toList().also { types.clear() }

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val packageName = classDeclaration.containingFile!!.packageName.asString()
        val name = classDeclaration.simpleName.asString()

        val key = Pair(packageName, name)
        if (processed.contains(key)) return
        processed.add(key)

        val annotation = classDeclaration.annotations.first { it.shortName.asString() == "PipelineNodeType" }
        val category = annotation.enumArg("category") ?: "TRANSFORM"
        val label = annotation.stringArg("label") ?: ""
        val description = annotation.stringArg("description") ?: ""
        val group = annotation.stringArg("group") ?: ""
        val subgroup = annotation.stringArg("subgroup") ?: ""
        val inputs = (annotation.arguments.firstOrNull { it.name?.asString() == "inputs" }?.value as? List<*>)
            .orEmpty()
            .filterIsInstance<KSAnnotation>()
            .map { slot ->
                FoundPipelineSlot(
                    name = slot.stringArg("name") ?: "",
                    kind = slot.enumArg("kind") ?: "ANY",
                    typeLabel = slot.stringArg("typeLabel") ?: "",
                    description = slot.stringArg("description") ?: "",
                    type = slot.classNameArg("type"),
                    schema = slot.stringArg("schema") ?: "",
                    required = slot.arguments.firstOrNull { it.name?.asString() == "required" }?.value as? Boolean ?: true,
                )
            }
        val outputs = (annotation.arguments.firstOrNull { it.name?.asString() == "outputs" }?.value as? List<*>)
            .orEmpty()
            .filterIsInstance<KSAnnotation>()
            .map { out ->
                FoundPipelineOutput(
                    name = out.stringArg("name") ?: "",
                    kind = out.enumArg("kind") ?: "ANY",
                    error = out.arguments.firstOrNull { it.name?.asString() == "error" }?.value as? Boolean ?: false,
                    type = out.classNameArg("type"),
                    typeLabel = out.stringArg("typeLabel") ?: "",
                    description = out.stringArg("description") ?: "",
                )
            }
        val settings = (annotation.arguments.firstOrNull { it.name?.asString() == "settings" }?.value as? List<*>)
            .orEmpty()
            .filterIsInstance<KSAnnotation>()
            .map { parseSetting(it) }
        settings.filter { it.name in RESERVED_SETTING_NAMES }.forEach { setting ->
            logger.error(
                "@PipelineNodeType setting name '${setting.name}' on $name is reserved — it collides with " +
                    "the stored node's '${setting.name}' key in the flat node JSON " +
                    "(type = the polymorphic discriminator; id/position = the node envelope). " +
                    "Rename the setting and its backing property (e.g. 'notificationType').",
                classDeclaration,
            )
        }
        val serialName = classDeclaration.annotations
            .firstOrNull { it.shortName.asString() == "SerialName" }
            ?.arguments?.firstOrNull()?.value as? String
            ?: name

        types.add(
            FoundPipelineNode(
                ClassName(packageName, name), serialName, category, label, description, group,
                subgroup, inputs, outputs, settings, classDeclaration,
            )
        )
    }

    /**
     * Decode one `@SettingSlot` (or nested `@SettingField` — same scalar args) into a
     * [FoundPipelineSetting]. `control` is the enum entry name; `reference` is the entry name or null
     * when `NONE`. Recurses into nested `@SettingOption`/`@SettingField` arrays the same way the node's
     * `inputs`/`outputs` arrays decode.
     */
    private fun parseSetting(s: KSAnnotation): FoundPipelineSetting =
        FoundPipelineSetting(
            name = s.stringArg("name") ?: "",
            control = s.enumArg("control") ?: "TEXT",
            label = s.stringArg("label") ?: "",
            description = s.stringArg("description") ?: "",
            placeholder = s.stringArg("placeholder") ?: "",
            default = s.stringArg("default") ?: "",
            required = s.booleanArg("required") ?: false,
            secret = s.booleanArg("secret") ?: false,
            mono = s.booleanArg("mono") ?: false,
            language = s.stringArg("language") ?: "",
            reference = s.enumArg("reference")?.takeIf { it != "NONE" },
            options = (s.arguments.firstOrNull { it.name?.asString() == "options" }?.value as? List<*>)
                .orEmpty()
                .filterIsInstance<KSAnnotation>()
                .map { FoundPipelineOption(it.stringArg("value") ?: "", it.stringArg("label") ?: "") },
            fields = (s.arguments.firstOrNull { it.name?.asString() == "fields" }?.value as? List<*>)
                .orEmpty()
                .filterIsInstance<KSAnnotation>()
                .map { parseSetting(it) },
            itemLabel = s.stringArg("itemLabel") ?: "",
            group = s.stringArg("group") ?: "",
            visibleWhenSetting = s.stringArg("visibleWhenSetting") ?: "",
            visibleWhenEquals = s.stringArg("visibleWhenEquals") ?: "",
        )

    private fun KSAnnotation.stringArg(arg: String): String? =
        arguments.firstOrNull { it.name?.asString() == arg }?.value as? String

    private fun KSAnnotation.booleanArg(arg: String): Boolean? =
        arguments.firstOrNull { it.name?.asString() == arg }?.value as? Boolean

    private fun KSAnnotation.enumArg(arg: String): String? =
        (arguments.firstOrNull { it.name?.asString() == arg }?.value as? KSClassDeclaration)?.simpleName?.asString()

    /**
     * Resolves a `KClass<*>` annotation arg to its [ClassName] (so the generator can emit
     * `<type>.serializer().descriptor.serialName` — the authoritative, reflection-free serial name the
     * runtime value also reports). The `Unit` sentinel (and an absent arg) → null = none.
     */
    private fun KSAnnotation.classNameArg(arg: String): ClassName? {
        val decl = (arguments.firstOrNull { it.name?.asString() == arg }?.value as? KSType)
            ?.declaration as? KSClassDeclaration ?: return null
        if (decl.qualifiedName?.asString() == "kotlin.Unit") return null
        return ClassName(decl.packageName.asString(), decl.simpleName.asString())
    }
}
