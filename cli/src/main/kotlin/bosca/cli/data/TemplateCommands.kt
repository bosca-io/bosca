package bosca.cli.data

import bosca.cli.BoscaCliCommand
import bosca.cli.api.ContentApi
import bosca.cli.data.ContentConverters.toAttributeLocation
import bosca.cli.data.ContentConverters.toAttributeType
import bosca.cli.data.ContentConverters.toAttributeUiType
import bosca.cli.data.ContentConverters.toContainerType
import bosca.cli.data.ContentConverters.toDataType
import bosca.cli.data.ContentConverters.toGuideType
import bosca.cli.data.ContentConverters.toOrder
import bosca.cli.data.ContentConverters.templateEditorAttributes
import bosca.graphql.gen.*
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.json.*

class TemplateCommand : BoscaCliCommand(name = "template") {
    override fun help(context: Context) = "Manage content templates (document, guide, data, collection)"
    override fun run() = Unit
}

class TemplateListCommand : DataSubcommand("list") {
    override fun help(context: Context) = "List available content templates"

    private val type by option("--type", help = "Filter by type: DOCUMENT, GUIDE, DATA, COLLECTION")

    override suspend fun execute(api: ContentApi) {
        val contentTypes = when (type?.uppercase()) {
            "DOCUMENT" -> listOf("bosca/v-document-template")
            "GUIDE" -> listOf("bosca/v-guide-template")
            "DATA" -> listOf("bosca/v-data-template")
            "COLLECTION" -> listOf("bosca/v-collection-template")
            null -> listOf(
                "bosca/v-document-template",
                "bosca/v-guide-template",
                "bosca/v-data-template",
                "bosca/v-collection-template",
            )
            else -> error("Unknown type: $type. Use DOCUMENT, GUIDE, DATA, or COLLECTION.")
        }
        val templates = api.metadata.findMetadata(contentTypes = contentTypes, offset = 0, limit = 100)
        for (t in templates) {
            echo("${t.id}  ${(t.content?.type ?: "").removePrefix("bosca/v-").removeSuffix("-template").uppercase().padEnd(12)}  ${t.name}")
        }
        if (templates.isEmpty()) echo("No templates found.")
    }
}

class TemplateGetCommand : DataSubcommand("get") {
    override fun help(context: Context) = "Get a template's full schema and details"

    private val id by option("--id", help = "Template metadata UUID").required()

    override suspend fun execute(api: ContentApi) {
        val uuid = kotlin.uuid.Uuid.parse(id)
        val meta = api.metadata.get(uuid) ?: error("Template not found: $id")

        echo("Template: ${meta.name}")
        echo("  ID: ${meta.id}")
        echo("  Type: ${meta.content?.type}")
        echo("  Version: ${meta.version}")

        when (meta.content?.type) {
            "bosca/v-document-template" -> {
                val dt = api.metadata.getDocumentTemplate(uuid, meta.version)
                if (dt != null) {
                    echo("  Attributes:")
                    for (a in dt.attributes) {
                        echo("    ${a.key}: ${a.type.name} (${a.ui.name}) — ${a.name}")
                    }
                }
            }
            "bosca/v-guide-template" -> {
                val gt = api.metadata.getGuideTemplate(uuid, meta.version)
                if (gt != null) {
                    echo("  Guide Type: ${gt.type.name}")
                    echo("  Steps: ${gt.steps.size}")
                    for ((i, s) in gt.steps.withIndex()) {
                        echo("    Step ${i + 1}: template=${s.metadata?.name ?: "unknown"}, ${s.modules.size} modules")
                    }
                }
            }
            "bosca/v-data-template" -> {
                echo("  Attributes: ${meta.attributes}")
            }
            "bosca/v-collection-template" -> {
                val ct = api.metadata.getCollectionTemplate(uuid)
                echo("  Attributes:")
                for (a in ct.attributes) {
                    echo("    ${a.key}: ${a.type.name} (${a.ui.name}) — ${a.name}")
                }
            }
        }
    }
}

class TemplateCreateDocumentCommand : DataSubcommand("create-document") {
    override fun help(context: Context) = "Create a document template"

    private val name by option("--name", help = "Template name").required()
    private val slug by option("--slug", help = "URL-friendly slug")
    private val languageTag by option("--language-tag", help = "BCP 47 language tag").default("en")
    private val attributesJson by option("--attributes-json", help = "Template attributes as JSON array")
    private val containersJson by option("--containers-json", help = "Containers as JSON array")
    private val defaultContentJson by option("--default-content-json", help = "Default ProseMirror content as JSON")
    private val schemaJson by option("--schema-json", help = "Validation schema as JSON")

    override suspend fun execute(api: ContentApi) {
        val json = Json { ignoreUnknownKeys = true }
        val attrs = attributesJson?.let { json.decodeFromString<List<JsonObject>>(it) }
            ?.map { it.toAttrInput() } ?: emptyList()
        val containers = containersJson?.let { json.decodeFromString<List<JsonObject>>(it) }
            ?.map { it.toContainerInput() } ?: emptyList()
        val defaultContent = defaultContentJson?.let { json.decodeFromString<JsonElement>(it) }

        val templateInput = DocumentTemplateInput(
            attributes = attrs,
            configuration = null,
            containers = containers,
            content = JsonObject(mapOf("document" to (defaultContent ?: JsonObject(emptyMap())))),
            defaultAttributes = null,
            schema = schemaJson?.let { json.decodeFromString<JsonElement>(it) },
        )
        val metadataInput = MetadataInput(
            name = name,
            contentType = "bosca/v-document-template",
            languageTag = languageTag,
            slug = slug,
            documentTemplate = templateInput,
            attributes = templateEditorAttributes("Document"),
        )
        val id = api.metadata.add(metadataInput) ?: error("Failed to create document template")
        api.metadata.setReady(id)
        echo("Created document template: $id")
    }
}

class TemplateCreateGuideCommand : DataSubcommand("create-guide") {
    override fun help(context: Context) = "Create a guide template"

    private val name by option("--name", help = "Template name").required()
    private val slug by option("--slug", help = "URL-friendly slug")
    private val languageTag by option("--language-tag", help = "BCP 47 language tag").default("en")
    private val guideType by option("--guide-type", help = "LINEAR, LINEAR_PROGRESS, CALENDAR, CALENDAR_PROGRESS").default("LINEAR")
    private val rrule by option("--rrule", help = "iCalendar recurrence rule")
    private val stepsJson by option("--steps-json", help = "Steps as JSON array [{templateId, templateVersion, modules?}]")
    private val attributesJson by option("--attributes-json", help = "Template attributes as JSON array")

    override suspend fun execute(api: ContentApi) {
        val json = Json { ignoreUnknownKeys = true }
        val steps = stepsJson?.let { json.decodeFromString<List<JsonObject>>(it) }?.map { step ->
            GuideTemplateStepInput(
                templateMetadataId = kotlin.uuid.Uuid.parse(step["templateId"]!!.jsonPrimitive.content),
                templateMetadataVersion = step["templateVersion"]?.jsonPrimitive?.int ?: 1,
                modules = (step["modules"] as? JsonArray)?.map { m ->
                    val mod = m.jsonObject
                    GuideTemplateStepModuleInput(
                        templateMetadataId = kotlin.uuid.Uuid.parse(mod["templateId"]!!.jsonPrimitive.content),
                        templateMetadataVersion = mod["templateVersion"]?.jsonPrimitive?.int ?: 1,
                    )
                } ?: emptyList(),
            )
        } ?: emptyList()

        val guideTemplateInput = GuideTemplateInput(
            configuration = null,
            defaultAttributes = null,
            rrule = rrule ?: "",
            steps = steps,
            type = guideType.toGuideType(),
        )
        val documentTemplateInput = DocumentTemplateInput(
            attributes = emptyList(),
            content = JsonObject(emptyMap()),
            configuration = null,
            containers = emptyList(),
            defaultAttributes = null,
            schema = null,
        )
        val metadataInput = MetadataInput(
            name = name,
            contentType = "bosca/v-guide-template",
            languageTag = languageTag,
            slug = slug,
            documentTemplate = documentTemplateInput,
            guideTemplate = guideTemplateInput,
            attributes = templateEditorAttributes("Guide"),
        )
        val id = api.metadata.add(metadataInput) ?: error("Failed to create guide template")

        val attrs = attributesJson?.let { json.decodeFromString<List<JsonObject>>(it) }
            ?.map { it.toAttrInput() } ?: emptyList()
        for ((index, attr) in attrs.withIndex()) {
            api.metadata.addGuideTemplateAttribute(id, 1, attr, index)
        }

        api.metadata.setReady(id)
        echo("Created guide template: $id")
    }
}

class TemplateCreateDataCommand : DataSubcommand("create-data") {
    override fun help(context: Context) = "Create a data template"

    private val name by option("--name", help = "Template name").required()
    private val slug by option("--slug", help = "URL-friendly slug")
    private val languageTag by option("--language-tag", help = "BCP 47 language tag").default("en")
    private val dataType by option("--data-type", help = "ATTRIBUTES or TABLE").default("ATTRIBUTES")
    private val attributesJson by option("--attributes-json", help = "Template attributes as JSON array")

    override suspend fun execute(api: ContentApi) {
        val json = Json { ignoreUnknownKeys = true }
        val attrs = attributesJson?.let { json.decodeFromString<List<JsonObject>>(it) }
            ?.map { it.toAttrInput() } ?: emptyList()

        val dataTemplateInput = DataTemplateInput(
            attributes = attrs,
            defaultAttributes = null,
            type = dataType.toDataType(),
        )
        val metadataInput = MetadataInput(
            name = name,
            contentType = "bosca/v-data-template",
            languageTag = languageTag,
            slug = slug,
            dataTemplate = dataTemplateInput,
            attributes = templateEditorAttributes("Data"),
        )
        val id = api.metadata.add(metadataInput) ?: error("Failed to create data template")
        api.metadata.setReady(id)
        echo("Created data template: $id")
    }
}

class TemplateCreateCollectionCommand : DataSubcommand("create-collection") {
    override fun help(context: Context) = "Create a collection template"

    private val name by option("--name", help = "Template name").required()
    private val slug by option("--slug", help = "URL-friendly slug")
    private val languageTag by option("--language-tag", help = "BCP 47 language tag").default("en")
    private val attributesJson by option("--attributes-json", help = "Template attributes as JSON array")
    private val filtersJson by option("--filters-json", help = "Filters as JSON array [{name, filter}]")
    private val orderingJson by option("--ordering-json", help = "Ordering as JSON array [{field, order}]")

    override suspend fun execute(api: ContentApi) {
        val json = Json { ignoreUnknownKeys = true }
        val attrs = attributesJson?.let { json.decodeFromString<List<JsonObject>>(it) }
            ?.map { it.toAttrInput() } ?: emptyList()
        val filters = filtersJson?.let { json.decodeFromString<List<JsonObject>>(it) }
        val ordering = orderingJson?.let { json.decodeFromString<List<JsonObject>>(it) }

        val filtersInput = filters?.takeIf { it.isNotEmpty() }?.let {
            CollectionTemplateFiltersInput(
                filters = it.map { f ->
                    CollectionTemplateFilterInput(
                        name = f["name"]!!.jsonPrimitive.content,
                        filter = f["filter"]!!.jsonPrimitive.content,
                    )
                }
            )
        }

        val orderingInput = ordering?.takeIf { it.isNotEmpty() }?.let {
            it.map { o ->
                OrderingInput(
                    field = o["field"]?.jsonPrimitive?.contentOrNull,
                    location = o["location"]?.jsonPrimitive?.contentOrNull?.toAttributeLocation(),
                    order = o["order"]?.jsonPrimitive?.contentOrNull?.toOrder(),
                    path = (o["path"] as? JsonArray)?.map { it.jsonPrimitive.content },
                    type = o["type"]?.jsonPrimitive?.contentOrNull?.toAttributeType(),
                )
            }
        }

        val templateInput = CollectionTemplateInput(
            attributes = attrs,
            configuration = null,
            defaultAttributes = null,
            filters = filtersInput,
            ordering = orderingInput,
        )
        val metadataInput = MetadataInput(
            name = name,
            contentType = "bosca/v-collection-template",
            languageTag = languageTag,
            slug = slug,
            collectionTemplate = templateInput,
            attributes = templateEditorAttributes("Collection"),
        )
        val id = api.metadata.add(metadataInput) ?: error("Failed to create collection template")
        api.metadata.setReady(id)
        echo("Created collection template: $id")
    }
}

private fun JsonObject.toAttrInput() = TemplateAttributeInput(
    key = this["key"]!!.jsonPrimitive.content,
    name = this["name"]!!.jsonPrimitive.content,
    description = this["description"]?.jsonPrimitive?.contentOrNull ?: "",
    type = this["type"]!!.jsonPrimitive.content.toAttributeType(),
    ui = this["ui"]!!.jsonPrimitive.content.toAttributeUiType(),
    list = this["list"]?.jsonPrimitive?.booleanOrNull ?: false,
    location = this["location"]?.jsonPrimitive?.contentOrNull?.toAttributeLocation(),
    configuration = this["configuration"] as? JsonObject,
)

private fun JsonObject.toContainerInput() = DocumentTemplateContainerInput(
    id = this["id"]!!.jsonPrimitive.content,
    name = this["name"]!!.jsonPrimitive.content,
    description = this["description"]?.jsonPrimitive?.contentOrNull ?: "",
    containerType = this["type"]?.jsonPrimitive?.contentOrNull?.toContainerType(),
    supplementaryKey = this["supplementaryKey"]?.jsonPrimitive?.contentOrNull,
    workflows = emptyList(),
    filters = (this["filters"] as? JsonArray)?.map { it.jsonPrimitive.content },
)
