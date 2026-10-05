package bosca.cli.mcp

import bosca.cli.api.ContentApi
import bosca.cli.data.ContentConverters.toAttributeLocation
import bosca.cli.data.ContentConverters.toAttributeType
import bosca.cli.data.ContentConverters.toAttributeUiType
import bosca.cli.data.ContentConverters.toCollectionType
import bosca.cli.data.ContentConverters.toContainerType
import bosca.cli.data.ContentConverters.toDataType
import bosca.cli.data.ContentConverters.toGuideType
import bosca.cli.data.ContentConverters.toOrder
import bosca.cli.data.ContentConverters.templateEditorAttributes
import bosca.graphql.gen.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.io.File
import kotlin.uuid.Uuid

/**
 * Registers coarse-grained MCP tools for the Bosca content management
 * surface. Each tool maps to one or more ContentApi operations, selected
 * by an action parameter to keep the tool count AI-manageable.
 */
object ContentToolRegistrar {

    fun registerAll(server: Server, api: ContentApi) {
        registerTemplateTools(server, api)
        registerMetadataTools(server, api)
        registerCollectionTools(server, api)
        registerSupplementaryTools(server, api)
        registerRelationshipTools(server, api)
    }

    // ── Templates ──────────────────────────────────────────────

    private fun registerTemplateTools(server: Server, api: ContentApi) {
        server.addTool(
            Tool(
                name = "content_template",
                description = "Manage content templates (document, guide, data, collection). " +
                    "Use 'list' to discover available templates. Use 'get' to fetch a template's full schema " +
                    "(attributes, containers, steps) so you know what fields to populate when creating content. " +
                    "Use create_document/create_guide/create_data/create_collection to create new templates.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf(
                                "list", "get",
                                "create_document", "create_guide", "create_data", "create_collection",
                            ).map { JsonPrimitive(it) }))
                            put("description", "Operation to perform")
                        })
                        put("id", buildJsonObject { put("type", "string"); put("description", "Template metadata UUID (for get)") })
                        put("templateType", buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf("DOCUMENT", "GUIDE", "DATA", "COLLECTION").map { JsonPrimitive(it) }))
                            put("description", "Filter templates by type (for list)")
                        })
                        put("name", buildJsonObject { put("type", "string"); put("description", "Template name (for create)") })
                        put("languageTag", buildJsonObject { put("type", "string"); put("description", "BCP 47 language tag (default: en)") })
                        put("slug", buildJsonObject { put("type", "string"); put("description", "URL-friendly slug") })
                        put("attributes", buildJsonObject {
                            put("type", "array")
                            put("description", "Template attribute definitions. Each: {key, name, description, type (STRING/INT/FLOAT/DATE/DATETIME/METADATA/COLLECTION/PROFILE), ui (INPUT/TEXTAREA/IMAGE/FILE/COLLECTION/METADATA/PROFILE), list?, location? (ITEM/RELATIONSHIP), configuration?}")
                            put("items", buildJsonObject { put("type", "object") })
                        })
                        put("containers", buildJsonObject {
                            put("type", "array")
                            put("description", "Document template containers. Each: {id, name, description, type? (STANDARD/BIBLE/METADATA), supplementaryKey?, filters?}")
                            put("items", buildJsonObject { put("type", "object") })
                        })
                        put("defaultContent", buildJsonObject { put("type", "object"); put("description", "Default ProseMirror/TipTap JSON content tree (create_document)") })
                        put("defaultAttributes", buildJsonObject { put("type", "object"); put("description", "Default attribute values") })
                        put("schema", buildJsonObject { put("type", "object"); put("description", "JSON schema for content validation (create_document)") })
                        put("configuration", buildJsonObject { put("type", "object"); put("description", "Template-specific configuration") })
                        put("steps", buildJsonObject {
                            put("type", "array")
                            put("description", "Guide template steps. Each: {templateId (document template UUID), templateVersion (int), modules?: [{templateId, templateVersion}]}")
                            put("items", buildJsonObject { put("type", "object") })
                        })
                        put("guideType", buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf("LINEAR", "LINEAR_PROGRESS", "CALENDAR", "CALENDAR_PROGRESS").map { JsonPrimitive(it) }))
                            put("description", "Guide progression type (create_guide)")
                        })
                        put("rrule", buildJsonObject { put("type", "string"); put("description", "iCalendar recurrence rule (create_guide, CALENDAR types)") })
                        put("dataType", buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf("ATTRIBUTES", "TABLE").map { JsonPrimitive(it) }))
                            put("description", "Data storage type (create_data)")
                        })
                        put("filters", buildJsonObject {
                            put("type", "array")
                            put("description", "Collection template filters. Each: {name, filter}")
                            put("items", buildJsonObject { put("type", "object") })
                        })
                        put("ordering", buildJsonObject {
                            put("type", "array")
                            put("description", "Collection template ordering. Each: {field?, location?, order? (ASCENDING/DESCENDING), path?, type?}")
                            put("items", buildJsonObject { put("type", "object") })
                        })
                        put("traitIds", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "list" -> listTemplates(api, args)
                    "get" -> getTemplate(api, args)
                    "create_document" -> createDocumentTemplate(api, args)
                    "create_guide" -> createGuideTemplate(api, args)
                    "create_data" -> createDataTemplate(api, args)
                    "create_collection" -> createCollectionTemplate(api, args)
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    private suspend fun listTemplates(api: ContentApi, args: Map<String, JsonElement>): String {
        val typeFilter = args.optStr("templateType")
        val contentTypes = when (typeFilter?.uppercase()) {
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
            else -> error("Unknown templateType: $typeFilter. Use DOCUMENT, GUIDE, DATA, or COLLECTION.")
        }
        val templates = api.metadata.findMetadata(contentTypes = contentTypes, offset = 0, limit = 100)
        return json.encodeToString(JsonArray.serializer(), JsonArray(templates.map { t ->
            buildJsonObject {
                put("id", t.id.toString())
                put("name", t.name)
                put("contentType", t.content?.type ?: "")
                put("languageTag", t.languageTag)
                put("version", t.version)
            }
        }))
    }

    private suspend fun getTemplate(api: ContentApi, args: Map<String, JsonElement>): String {
        val id = Uuid.parse(args.str("id"))
        val meta = api.metadata.get(id) ?: error("Template not found: $id")
        val result = buildJsonObject {
            put("id", meta.id.toString())
            put("name", meta.name)
            put("contentType", meta.content?.type ?: "")
            put("languageTag", meta.languageTag)
            put("version", meta.version)
            put("attributes", meta.attributes ?: JsonNull)

            when (meta.content?.type ?: "") {
                "bosca/v-document-template" -> {
                    val dt = api.metadata.getDocumentTemplate(id, meta.version)
                    if (dt != null) {
                        put("templateType", "DOCUMENT")
                        put("templateAttributes", JsonArray(dt.attributes.map { a ->
                            buildJsonObject {
                                put("key", a.key); put("name", a.name); put("description", a.description)
                                put("type", a.type.name); put("ui", a.ui.name)
                            }
                        }))
                        put("schema", dt.schema ?: JsonNull)
                        put("defaultContent", dt.content)
                    }
                }
                "bosca/v-guide-template" -> {
                    val gt = api.metadata.getGuideTemplate(id, meta.version)
                    if (gt != null) {
                        put("templateType", "GUIDE")
                        put("guideType", gt.type.name)
                        put("rrule", gt.rrule)
                        put("steps", JsonArray(gt.steps.map { s ->
                            val step = s
                            buildJsonObject {
                                put("id", step.id)
                                put("templateMetadataId", step.metadata?.id?.toString())
                                put("templateMetadataName", step.metadata?.name)
                                put("modules", JsonArray(step.modules.map { m ->
                                    val mod = m
                                    buildJsonObject {
                                        put("id", mod.id)
                                        put("templateMetadataId", mod.metadata?.id?.toString())
                                        put("templateMetadataName", mod.metadata?.name)
                                    }
                                }))
                            }
                        }))
                    }
                }
                "bosca/v-data-template" -> {
                    put("templateType", "DATA")
                    // Data template attributes are on the metadata itself
                }
                "bosca/v-collection-template" -> {
                    val ct = api.metadata.getCollectionTemplate(id)
                    put("templateType", "COLLECTION")
                    put("templateAttributes", JsonArray(ct.attributes.map { a ->
                        buildJsonObject {
                            put("key", a.key); put("name", a.name); put("description", a.description)
                            put("type", a.type.name); put("ui", a.ui.name)
                            put("list", a.list)
                        }
                    }))
                }
            }
        }
        return json.encodeToString(JsonElement.serializer(), result)
    }

    private suspend fun createDocumentTemplate(api: ContentApi, args: Map<String, JsonElement>): String {
        val attrs = args.optJsonArray("attributes")?.map { it.jsonObject.toTemplateAttributeInput() } ?: emptyList()
        val containers = args.optJsonArray("containers")?.map { it.jsonObject.toDocumentContainerInput() } ?: emptyList()
        val templateInput = DocumentTemplateInput(
            attributes = attrs,
            configuration = args.optJsonObject("configuration"),
            containers = containers,
            content = JsonObject(mapOf("document" to (args.optJsonObject("defaultContent") ?: JsonObject(emptyMap())))),
            defaultAttributes = args.optJsonObject("defaultAttributes"),
            schema = args.optJsonObject("schema"),
        )
        val metadataInput = MetadataInput(
            name = args.str("name"),
            contentType = "bosca/v-document-template",
            languageTag = args.optStr("languageTag") ?: "en",
            slug = args.optStr("slug"),
            documentTemplate = templateInput,
            traitIds = args.optStringArray("traitIds"),
            attributes = templateEditorAttributes("Document"),
        )
        val id = api.metadata.add(metadataInput) ?: error("Failed to create document template")
        api.metadata.setReady(id)
        return json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("id", id.toString()); put("name", args.str("name")); put("status", "created")
        })
    }

    private suspend fun createGuideTemplate(api: ContentApi, args: Map<String, JsonElement>): String {
        val steps = args.optJsonArray("steps")?.map { it.jsonObject }?.map { step ->
            GuideTemplateStepInput(
                templateMetadataId = Uuid.parse(step.str("templateId")),
                templateMetadataVersion = step["templateVersion"]?.jsonPrimitive?.int ?: 1,
                modules = (step["modules"] as? JsonArray)?.map { m ->
                    val mod = m.jsonObject
                    GuideTemplateStepModuleInput(
                        templateMetadataId = Uuid.parse(mod.str("templateId")),
                        templateMetadataVersion = mod["templateVersion"]?.jsonPrimitive?.int ?: 1,
                    )
                } ?: emptyList(),
            )
        } ?: emptyList()
        val guideTemplateInput = GuideTemplateInput(
            configuration = args.optJsonObject("configuration"),
            defaultAttributes = args.optJsonObject("defaultAttributes"),
            rrule = args.optStr("rrule") ?: "",
            steps = steps,
            type = (args.optStr("guideType") ?: "LINEAR").toGuideType(),
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
            name = args.str("name"),
            contentType = "bosca/v-guide-template",
            languageTag = args.optStr("languageTag") ?: "en",
            slug = args.optStr("slug"),
            documentTemplate = documentTemplateInput,
            guideTemplate = guideTemplateInput,
            traitIds = args.optStringArray("traitIds"),
            attributes = templateEditorAttributes("Guide"),
        )
        val id = api.metadata.add(metadataInput) ?: error("Failed to create guide template")

        val attrs = args.optJsonArray("attributes")?.map { it.jsonObject.toTemplateAttributeInput() } ?: emptyList()
        for ((index, attr) in attrs.withIndex()) {
            api.metadata.addGuideTemplateAttribute(id, 1, attr, index)
        }

        api.metadata.setReady(id)
        return json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("id", id.toString()); put("name", args.str("name")); put("status", "created")
        })
    }

    private suspend fun createDataTemplate(api: ContentApi, args: Map<String, JsonElement>): String {
        val attrs = args.optJsonArray("attributes")?.map { it.jsonObject.toTemplateAttributeInput() } ?: emptyList()
        val dataTemplateInput = DataTemplateInput(
            attributes = attrs,
            defaultAttributes = args.optJsonObject("defaultAttributes"),
            type = (args.optStr("dataType") ?: "ATTRIBUTES").toDataType(),
        )
        val metadataInput = MetadataInput(
            name = args.str("name"),
            contentType = "bosca/v-data-template",
            languageTag = args.optStr("languageTag") ?: "en",
            slug = args.optStr("slug"),
            dataTemplate = dataTemplateInput,
            traitIds = args.optStringArray("traitIds"),
            attributes = templateEditorAttributes("Data"),
        )
        val id = api.metadata.add(metadataInput) ?: error("Failed to create data template")
        api.metadata.setReady(id)
        return json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("id", id.toString()); put("name", args.str("name")); put("status", "created")
        })
    }

    private suspend fun createCollectionTemplate(api: ContentApi, args: Map<String, JsonElement>): String {
        val attrs = args.optJsonArray("attributes")?.map { it.jsonObject.toTemplateAttributeInput() } ?: emptyList()
        val filtersInput = args.optJsonArray("filters")?.map { it.jsonObject }?.let { filterList ->
            if (filterList.isNotEmpty()) {
                CollectionTemplateFiltersInput(
                    filters = filterList.map { f ->
                        CollectionTemplateFilterInput(
                            name = f.str("name"),
                            filter = f.str("filter"),
                        )
                    }
                )
            } else null
        }
        val orderingInput = args.optJsonArray("ordering")?.map { it.jsonObject }?.let { orderList ->
            if (orderList.isNotEmpty()) {
                orderList.map { o ->
                    OrderingInput(
                        field = o.optStr("field"),
                        location = o.optStr("location")?.toAttributeLocation(),
                        order = o.optStr("order")?.toOrder(),
                        path = o["path"]?.let { p ->
                            (p as? JsonArray)?.map { it.jsonPrimitive.content }
                        },
                        type = o.optStr("type")?.toAttributeType(),
                    )
                }
            } else null
        }
        val templateInput = CollectionTemplateInput(
            attributes = attrs,
            configuration = args.optJsonObject("configuration"),
            defaultAttributes = args.optJsonObject("defaultAttributes"),
            filters = filtersInput,
            ordering = orderingInput,
        )
        val metadataInput = MetadataInput(
            name = args.str("name"),
            contentType = "bosca/v-collection-template",
            languageTag = args.optStr("languageTag") ?: "en",
            slug = args.optStr("slug"),
            collectionTemplate = templateInput,
            traitIds = args.optStringArray("traitIds"),
            attributes = templateEditorAttributes("Collection"),
        )
        val id = api.metadata.add(metadataInput) ?: error("Failed to create collection template")
        api.metadata.setReady(id)
        return json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("id", id.toString()); put("name", args.str("name")); put("status", "created")
        })
    }

    // ── Metadata ───────────────────────────────────────────────

    private fun registerMetadataTools(server: Server, api: ContentApi) {
        server.addTool(
            Tool(
                name = "content_metadata",
                description = "Manage content metadata items (documents, guides, data, media). " +
                    "Use 'create' to create items with optional inline document content. " +
                    "Use 'set_content' to set the body. For spec/requirement/document-like artifacts (anything that should render as a structured doc), the canonical path is `documentContent` — the typed Bosca Document model wrapped as `{ document: { type: 'doc', content: [...] } }`, routed through setMetadataDocument(DocumentInput) so it deserializes against the typed schema. `markdownContent` is also supported as a convenience (server-side converts markdown → typed Document via MarkdownConverter). `textContent`, `jsonContent`, and `filePath` are reserved for non-document metadata (raw text blobs, opaque JSON, image/video/file uploads) and must not be used for document bodies — they bypass the Document model and won't render as a structured doc. " +
                    "Use 'get_document' or 'get_guide' to fetch rich content details.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf(
                                "list", "get", "create", "edit", "delete",
                                "set_content", "set_attributes", "set_ready",
                                "get_document", "get_guide",
                            ).map { JsonPrimitive(it) }))
                        })
                        put("id", buildJsonObject { put("type", "string"); put("description", "Metadata UUID") })
                        put("slug", buildJsonObject { put("type", "string"); put("description", "Metadata slug (for get)") })
                        put("name", buildJsonObject { put("type", "string"); put("description", "Display name") })
                        put("contentType", buildJsonObject { put("type", "string"); put("description", "MIME content type (e.g. text/html, image/jpeg)") })
                        put("languageTag", buildJsonObject { put("type", "string"); put("description", "BCP 47 tag (default: en)") })
                        put("parentCollectionId", buildJsonObject { put("type", "string"); put("description", "Parent collection UUID") })
                        put("templateId", buildJsonObject { put("type", "string"); put("description", "Template metadata UUID") })
                        put("templateVersion", buildJsonObject { put("type", "integer"); put("description", "Template version (default: 1)") })
                        put("documentTitle", buildJsonObject { put("type", "string"); put("description", "Document title (create with document)") })
                        put("documentContent", buildJsonObject { put("type", "object"); put("description",
                            "Typed Bosca Document content (used by both `create` and `set_content`). PREFERRED path " +
                            "for any document-shaped metadata (specs, requirements, articles, guides). " +
                            "Shape MUST be the Content wrapper:\n" +
                            "  { \"document\": { \"type\": \"doc\", \"attrs\": {}, \"content\": [ ...nodes ], \"marks\": [] } }\n" +
                            "Routed through setMetadataDocument(DocumentInput) so it deserializes to Bosca's typed " +
                            "Document model (bosca.documents.*). Node SerialNames: heading (attrs.level 1-6, attrs.textAlign?), " +
                            "paragraph (attrs.textAlign?), text (text field; marks: bold|italic|underline|strike|code|link|" +
                            "superscript|subscript|hidden), bulletList, orderedList (attrs.start?), listItem, blockquote, " +
                            "codeBlock (attrs.language?), table, tableRow, tableHeader (attrs.colspan, attrs.rowspan), " +
                            "tableCell (attrs.colspan, attrs.rowspan), hardBreak, horizontalRule, " +
                            "container (attrs.name?, metadataId?, references?, renderer?), mention, image (attrs.src, " +
                            "alt?, title?, metadataId?), bible, html (attrs.html), taskList, taskItem (attrs.checked). " +
                            "All node/mark types correspond to standard TipTap extensions loaded by Bosca Studio " +
                            "(@tiptap/starter-kit + @tiptap/extension-{table,table-row,table-header,table-cell," +
                            "task-list,task-item,underline,superscript,subscript,link,text-align}). " +
                            "Pass `documentTitle` to title the document. Optionally bind to a template via " +
                            "`templateId` + `templateVersion`.") })
                        put("textContent", buildJsonObject { put("type", "string"); put("description",
                            "Plain text content (set_content). For NON-document metadata only — raw text blobs, plain " +
                            "data items. NEVER use for spec/requirement/document-like artifacts; it bypasses the typed " +
                            "Document model and won't render as a structured doc. Use `documentContent` (preferred) or " +
                            "`markdownContent` for document bodies.") })
                        put("jsonContent", buildJsonObject { put("type", "object"); put("description",
                            "Opaque structured JSON content (set_content). For NON-document metadata only — config " +
                            "blobs, raw data items. NEVER use for document bodies (specs, requirements, articles); " +
                            "use `documentContent` instead, which routes through the typed Document model.") })
                        put("markdownContent", buildJsonObject { put("type", "string"); put("description",
                            "Markdown source (set_content). Server converts via MarkdownConverter into the typed " +
                            "Bosca Document model and stores it the same way as `documentContent`. Convenient when " +
                            "you only have markdown text; prefer `documentContent` when you can build the typed " +
                            "Document directly. Provide `documentTitle` to title the document.") })
                        put("filePath", buildJsonObject { put("type", "string"); put("description", "Local file path for upload (set_content) — for binary content like images/videos.") })
                        put("attributes", buildJsonObject { put("type", "object"); put("description", "Custom attributes (JSON object)") })
                        put("traitIds", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                        put("public", buildJsonObject { put("type", "boolean") })
                        put("publicContent", buildJsonObject { put("type", "boolean") })
                        put("publicSupplementary", buildJsonObject { put("type", "boolean") })
                        put("version", buildJsonObject { put("type", "integer"); put("description", "Content version (for get_document/get_guide)") })
                        put("offset", buildJsonObject { put("type", "integer") })
                        put("limit", buildJsonObject { put("type", "integer") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "list" -> {
                        val offset = args.optInt("offset") ?: 0
                        val limit = args.optInt("limit") ?: 20
                        val contentTypes = args.optStr("contentType")?.let { listOf(it) }
                        val traitIds = args.optStringArray("traitIds")
                        val items = api.metadata.findMetadata(
                            contentTypes = contentTypes,
                            traitIds = traitIds,
                            offset = offset,
                            limit = limit,
                        )
                        json.encodeToString(JsonArray.serializer(), JsonArray(items.map {
                            metadataSummaryJson(it.id.toString(), it.name, it.content?.type ?: "", it.languageTag, it.version, it.metadataWorkflow.state)
                        }))
                    }
                    "get" -> {
                        val byId = args.optStr("id")?.let { api.metadata.get(Uuid.parse(it)) }
                        val detail = if (byId != null) {
                            metadataDetailJson(byId.id.toString(), byId.name, byId.content?.type ?: "", byId.languageTag, byId.version, byId.attributes, byId.labels, byId.public, byId.publicContent, byId.publicSupplementary, byId.metadataWorkflow.state, byId.created.toString(), byId.modified.toString())
                        } else {
                            val bySlug = args.optStr("slug")?.let { api.metadata.getBySlug(it) } ?: error("Provide id or slug")
                            metadataDetailJson(bySlug.id.toString(), bySlug.name, bySlug.content?.type ?: "", bySlug.languageTag, bySlug.version, bySlug.attributes, bySlug.labels, bySlug.public, bySlug.publicContent, bySlug.publicSupplementary, bySlug.metadataWorkflow.state, bySlug.created.toString(), bySlug.modified.toString())
                        }
                        json.encodeToString(JsonElement.serializer(), detail)
                    }
                    "create" -> {
                        val templateId = args.optStr("templateId")?.let { Uuid.parse(it) }
                        val templateVersion = args.optInt("templateVersion") ?: 1
                        val documentInput = args.optStr("documentTitle")?.let { title ->
                            DocumentInput(
                                title = title,
                                content = args.optJsonObject("documentContent") ?: JsonObject(emptyMap()),
                                templateMetadataId = templateId,
                                templateMetadataVersion = templateVersion,
                            )
                        }
                        val metadataInput = MetadataInput(
                            name = args.str("name"),
                            contentType = args.optStr("contentType") ?: "text/html",
                            languageTag = args.optStr("languageTag") ?: "en",
                            slug = args.optStr("slug"),
                            parentCollectionId = args.optStr("parentCollectionId")?.let { Uuid.parse(it) },
                            document = documentInput,
                            attributes = args.optJsonObject("attributes"),
                            traitIds = args.optStringArray("traitIds"),
                        )
                        val id = api.metadata.add(metadataInput) ?: error("Failed to create metadata")

                        if (documentInput != null) {
                            api.metadata.setDocument(id, 1, documentInput)
                        }

                        args.optBool("public")?.let { api.metadata.setPublic(id, it) }
                        args.optBool("publicContent")?.let { api.metadata.setPublicContent(id, it) }
                        args.optBool("publicSupplementary")?.let { api.metadata.setPublicSupplementary(id, it) }

                        buildJsonObject { put("id", id.toString()); put("name", args.str("name")); put("status", "created") }.toString()
                    }
                    "edit" -> {
                        val id = Uuid.parse(args.str("id"))
                        val existing = api.metadata.get(id) ?: error("Metadata not found: $id")
                        val metadataInput = MetadataInput(
                            name = args.optStr("name") ?: existing.name,
                            contentType = args.optStr("contentType") ?: existing.content?.type ?: "text/html",
                            languageTag = args.optStr("languageTag") ?: existing.languageTag,
                            slug = args.optStr("slug"),
                            attributes = args.optJsonObject("attributes"),
                            traitIds = args.optStringArray("traitIds"),
                        )
                        api.metadata.edit(id, metadataInput) ?: error("Failed to edit metadata: $id")

                        args.optBool("public")?.let { api.metadata.setPublic(id, it) }
                        args.optBool("publicContent")?.let { api.metadata.setPublicContent(id, it) }
                        args.optBool("publicSupplementary")?.let { api.metadata.setPublicSupplementary(id, it) }

                        buildJsonObject { put("id", id.toString()); put("status", "updated") }.toString()
                    }
                    "delete" -> {
                        api.metadata.delete(Uuid.parse(args.str("id")))
                        """{"deleted": true}"""
                    }
                    "set_content" -> {
                        val id = Uuid.parse(args.str("id"))
                        when {
                            args.optJsonObject("documentContent") != null -> {
                                val existing = api.metadata.get(id) ?: error("Metadata not found: $id")
                                val title = args.optStr("documentTitle") ?: existing.name
                                val tmplId = args.optStr("templateId")?.let { Uuid.parse(it) }
                                val tmplVersion = args.optInt("templateVersion")
                                val documentInput = DocumentInput(
                                    title = title,
                                    content = args["documentContent"]!!,
                                    templateMetadataId = tmplId,
                                    templateMetadataVersion = tmplVersion,
                                )
                                api.metadata.setDocument(id, existing.version, documentInput)
                                buildJsonObject { put("id", id.toString()); put("status", "document_set"); put("title", title) }.toString()
                            }
                            args.optStr("filePath") != null -> {
                                val file = File(args.str("filePath"))
                                if (!file.exists()) error("File not found: ${file.absolutePath}")
                                val ct = args.optStr("contentType") ?: file.detectContentType()
                                api.metadata.setFileContents(id, bosca.graphql.client.Upload(file.name, ct, file.readBytes()))
                                buildJsonObject { put("id", id.toString()); put("status", "uploaded"); put("contentType", ct) }.toString()
                            }
                            args.optStr("markdownContent") != null -> {
                                val existing = api.metadata.get(id) ?: error("Metadata not found: $id")
                                val title = args.optStr("documentTitle") ?: existing.name
                                api.metadata.setMarkdown(id, existing.version, title, args.str("markdownContent"))
                                buildJsonObject { put("id", id.toString()); put("status", "markdown_set"); put("title", title) }.toString()
                            }
                            args.optJsonObject("jsonContent") != null -> {
                                val ct = args.optStr("contentType") ?: "application/json"
                                api.metadata.setJsonContent(id, ct, args["jsonContent"]!!)
                                buildJsonObject { put("id", id.toString()); put("status", "json_set") }.toString()
                            }
                            args.optStr("textContent") != null -> {
                                val ct = args.optStr("contentType") ?: "text/plain"
                                api.metadata.setTextContent(id, ct, args.str("textContent"))
                                buildJsonObject { put("id", id.toString()); put("status", "text_set") }.toString()
                            }
                            else -> error("Provide documentContent, filePath, markdownContent, jsonContent, or textContent")
                        }
                    }
                    "set_attributes" -> {
                        val id = Uuid.parse(args.str("id"))
                        api.metadata.mergeAttributes(id, args["attributes"] ?: error("Provide attributes"))
                        buildJsonObject { put("id", id.toString()); put("status", "attributes_updated") }.toString()
                    }
                    "set_ready" -> {
                        val id = Uuid.parse(args.str("id"))
                        api.metadata.setReady(id)
                        buildJsonObject { put("id", id.toString()); put("status", "ready") }.toString()
                    }
                    "get_document" -> {
                        val id = Uuid.parse(args.str("id"))
                        val version = args.optInt("version") ?: api.metadata.get(id)?.version ?: error("Metadata not found")
                        val doc = api.metadata.getDocument(id, version) ?: error("Document not found")
                        buildJsonObject {
                            put("id", id.toString())
                            put("title", doc.title)
                            put("content", doc.content)
                            put("templateId", doc.template?.id?.toString())
                            put("templateVersion", doc.template?.version)
                        }.toString()
                    }
                    "get_guide" -> {
                        val id = Uuid.parse(args.str("id"))
                        val version = args.optInt("version") ?: api.metadata.get(id)?.version ?: error("Metadata not found")
                        val guide = api.metadata.getGuide(id, version) ?: error("Guide not found")
                        buildJsonObject {
                            put("id", id.toString())
                            put("type", guide.type.name)
                            put("rrule", guide.rrule)
                            put("templateId", guide.template?.id?.toString())
                            put("templateVersion", guide.template?.version)
                            put("steps", JsonArray(guide.steps.map { s ->
                                val step = s
                                buildJsonObject {
                                    put("metadataId", step.metadata?.id?.toString())
                                    put("metadataName", step.metadata?.name)
                                }
                            }))
                        }.toString()
                    }
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Collections ────────────────────────────────────────────

    private fun registerCollectionTools(server: Server, api: ContentApi) {
        server.addTool(
            Tool(
                name = "content_collection",
                description = "Manage content collections (folders, groups). " +
                    "Use 'list_items' to see what's in a collection. " +
                    "Use 'add_item'/'remove_item' to associate metadata or child collections.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf(
                                "list", "get", "create", "edit", "delete",
                                "add_item", "remove_item", "list_items", "set_ready",
                            ).map { JsonPrimitive(it) }))
                        })
                        put("id", buildJsonObject { put("type", "string"); put("description", "Collection UUID") })
                        put("name", buildJsonObject { put("type", "string") })
                        put("description", buildJsonObject { put("type", "string") })
                        put("slug", buildJsonObject { put("type", "string") })
                        put("collectionType", buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf("STANDARD", "FOLDER", "ROOT", "QUEUE", "SYSTEM").map { JsonPrimitive(it) }))
                        })
                        put("parentCollectionId", buildJsonObject { put("type", "string") })
                        put("templateId", buildJsonObject { put("type", "string"); put("description", "Collection template metadata UUID") })
                        put("templateVersion", buildJsonObject { put("type", "integer") })
                        put("attributes", buildJsonObject { put("type", "object") })
                        put("traitIds", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                        put("metadataId", buildJsonObject { put("type", "string"); put("description", "Metadata UUID (add_item/remove_item)") })
                        put("childCollectionId", buildJsonObject { put("type", "string"); put("description", "Child collection UUID (remove_item)") })
                        put("public", buildJsonObject { put("type", "boolean") })
                        put("publicList", buildJsonObject { put("type", "boolean") })
                        put("offset", buildJsonObject { put("type", "integer") })
                        put("limit", buildJsonObject { put("type", "integer") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "list" -> {
                        val offset = args.optInt("offset") ?: 0
                        val limit = args.optInt("limit") ?: 20
                        val items = api.collections.getAll(offset, limit)
                        json.encodeToString(JsonArray.serializer(), JsonArray(items.map { c ->
                            buildJsonObject {
                                put("id", c.id.toString()); put("name", c.name)
                                put("public", c.`public`)
                            }
                        }))
                    }
                    "get" -> {
                        val c = api.collections.get(Uuid.parse(args.str("id"))) ?: error("Collection not found")
                        buildJsonObject {
                            put("id", c.id.toString()); put("name", c.name)
                            put("public", c.`public`); put("publicList", c.publicList)
                            put("attributes", c.attributes ?: JsonNull)
                        }.toString()
                    }
                    "create" -> {
                        val collectionInput = CollectionInput(
                            name = args.str("name"),
                            description = args.optStr("description"),
                            slug = args.optStr("slug"),
                            collectionType = args.optStr("collectionType")?.toCollectionType(),
                            parentCollectionId = args.optStr("parentCollectionId"),
                            templateMetadataId = args.optStr("templateId")?.let { Uuid.parse(it) },
                            templateMetadataVersion = args.optInt("templateVersion"),
                            attributes = args.optJsonObject("attributes"),
                            traitIds = args.optStringArray("traitIds"),
                        )
                        val id = api.collections.add(collectionInput) ?: error("Failed to create collection")

                        args.optBool("public")?.let { api.collections.setPublic(id, it) }
                        args.optBool("publicList")?.let { api.collections.setPublicList(id, it) }

                        api.collections.setReady(id)
                        buildJsonObject { put("id", id.toString()); put("name", args.str("name")); put("status", "created") }.toString()
                    }
                    "edit" -> {
                        val id = Uuid.parse(args.str("id"))
                        val existing = api.collections.get(id) ?: error("Collection not found: $id")
                        val collectionInput = CollectionInput(
                            name = args.optStr("name") ?: existing.name,
                            description = args.optStr("description"),
                            slug = args.optStr("slug"),
                            attributes = args.optJsonObject("attributes"),
                            traitIds = args.optStringArray("traitIds"),
                        )
                        api.collections.edit(id, collectionInput) ?: error("Failed to edit collection: $id")

                        args.optBool("public")?.let { api.collections.setPublic(id, it) }
                        args.optBool("publicList")?.let { api.collections.setPublicList(id, it) }

                        buildJsonObject { put("id", id.toString()); put("status", "updated") }.toString()
                    }
                    "delete" -> {
                        api.collections.delete(Uuid.parse(args.str("id")))
                        """{"deleted": true}"""
                    }
                    "list_items" -> {
                        val collectionAndItems = api.collections.list(Uuid.parse(args.str("id")))
                            ?: error("Collection not found")
                        json.encodeToString(JsonArray.serializer(), JsonArray(collectionAndItems.items.map { item ->
                            buildJsonObject {
                                put("id", item.id.toString())
                                put("name", item.name)
                                put("isCollection", item.isCollection)
                            }
                        }))
                    }
                    "add_item" -> {
                        val collectionId = Uuid.parse(args.str("id"))
                        val metadataId = Uuid.parse(args.str("metadataId"))
                        api.collections.addMetadata(collectionId, metadataId)
                        buildJsonObject { put("collectionId", collectionId.toString()); put("metadataId", metadataId.toString()); put("status", "added") }.toString()
                    }
                    "remove_item" -> {
                        val collectionId = Uuid.parse(args.str("id"))
                        when {
                            args.optStr("metadataId") != null -> {
                                api.collections.removeMetadata(collectionId, Uuid.parse(args.str("metadataId")))
                            }
                            args.optStr("childCollectionId") != null -> {
                                api.collections.removeCollection(collectionId, Uuid.parse(args.str("childCollectionId")))
                            }
                            else -> error("Provide metadataId or childCollectionId")
                        }
                        """{"removed": true}"""
                    }
                    "set_ready" -> {
                        val id = Uuid.parse(args.str("id"))
                        api.collections.setReady(id)
                        buildJsonObject { put("id", id.toString()); put("status", "ready") }.toString()
                    }
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Supplementary ──────────────────────────────────────────

    private fun registerSupplementaryTools(server: Server, api: ContentApi) {
        server.addTool(
            Tool(
                name = "content_supplementary",
                description = "Manage supplementary content attached to metadata items — thumbnails, audio, " +
                    "transcripts, attachments. Use 'add' to create a supplementary slot, then 'set_content' to upload the file.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf("list", "add", "delete", "set_content").map { JsonPrimitive(it) }))
                        })
                        put("metadataId", buildJsonObject { put("type", "string"); put("description", "Parent metadata UUID") })
                        put("supplementaryId", buildJsonObject { put("type", "string"); put("description", "Supplementary UUID (delete/set_content)") })
                        put("key", buildJsonObject { put("type", "string"); put("description", "Unique key (e.g. thumbnail, audio, transcript)") })
                        put("name", buildJsonObject { put("type", "string"); put("description", "Display name") })
                        put("contentType", buildJsonObject { put("type", "string"); put("description", "MIME content type") })
                        put("filePath", buildJsonObject { put("type", "string"); put("description", "Local file path for upload") })
                        put("textContent", buildJsonObject { put("type", "string"); put("description", "Text content to set") })
                        put("attributes", buildJsonObject { put("type", "object") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "list" -> {
                        val metadataId = Uuid.parse(args.str("metadataId"))
                        val supps = api.metadata.getSupplementary(metadataId)
                        json.encodeToString(JsonArray.serializer(), JsonArray(supps.map { s ->
                            buildJsonObject {
                                put("id", s.id.toString()); put("key", s.key)
                                put("name", s.name)
                                put("contentType", s.content.type)
                                put("contentLength", s.content.length)
                            }
                        }))
                    }
                    "add" -> {
                        val metadataId = Uuid.parse(args.str("metadataId"))
                        val input = MetadataSupplementaryInput(
                            metadataId = metadataId,
                            key = args.str("key"),
                            name = args.str("name"),
                            contentType = args.optStr("contentType") ?: "application/octet-stream",
                            attributes = args.optJsonObject("attributes"),
                            contentLength = args.optStr("filePath")?.let { File(it).length().toInt() },
                            planId = metadataId,
                        )
                        val supp = api.metadata.addSupplementary(input)
                            ?: error("Failed to add supplementary")

                        // Auto-upload if filePath provided
                        args.optStr("filePath")?.let { path ->
                            val file = File(path)
                            if (file.exists()) {
                                val ct = args.optStr("contentType") ?: file.detectContentType()
                                api.metadata.setSupplementaryContents(supp.id, file, ct)
                            }
                        }

                        // Auto-set text if textContent provided
                        args.optStr("textContent")?.let { text ->
                            val ct = args.optStr("contentType") ?: "text/plain"
                            api.metadata.setSupplementaryTextContent(supp.id, ct, text)
                        }

                        buildJsonObject {
                            put("id", supp.id.toString()); put("key", supp.key)
                            put("status", "created")
                        }.toString()
                    }
                    "delete" -> {
                        api.metadata.deleteSupplementary(Uuid.parse(args.str("supplementaryId")))
                        """{"deleted": true}"""
                    }
                    "set_content" -> {
                        val suppId = Uuid.parse(args.str("supplementaryId"))
                        when {
                            args.optStr("filePath") != null -> {
                                val file = File(args.str("filePath"))
                                if (!file.exists()) error("File not found: ${file.absolutePath}")
                                val ct = args.optStr("contentType") ?: file.detectContentType()
                                api.metadata.setSupplementaryContents(suppId, file, ct)
                                buildJsonObject { put("id", suppId.toString()); put("status", "uploaded") }.toString()
                            }
                            args.optStr("textContent") != null -> {
                                val ct = args.optStr("contentType") ?: "text/plain"
                                api.metadata.setSupplementaryTextContent(suppId, ct, args.str("textContent"))
                                buildJsonObject { put("id", suppId.toString()); put("status", "text_set") }.toString()
                            }
                            else -> error("Provide filePath or textContent")
                        }
                    }
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Relationships ──────────────────────────────────────────

    private fun registerRelationshipTools(server: Server, api: ContentApi) {
        server.addTool(
            Tool(
                name = "content_relationship",
                description = "Manage directional relationships between content items. " +
                    "Relationships link metadata items with a named type (e.g. 'thumbnail', 'author', 'related') " +
                    "and can carry custom attributes on the relationship edge.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf("list", "list_inverse", "add", "remove", "merge_attributes").map { JsonPrimitive(it) }))
                        })
                        put("id", buildJsonObject { put("type", "string"); put("description", "Metadata UUID (for list/list_inverse)") })
                        put("id1", buildJsonObject { put("type", "string"); put("description", "Source metadata UUID") })
                        put("id2", buildJsonObject { put("type", "string"); put("description", "Target metadata UUID") })
                        put("relationship", buildJsonObject { put("type", "string"); put("description", "Relationship type name") })
                        put("attributes", buildJsonObject { put("type", "object"); put("description", "Relationship attributes (JSON)") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "list" -> {
                        val sourceId = Uuid.parse(args.str("id"))
                        val rels = api.metadata.getRelationships(sourceId)
                        json.encodeToString(JsonArray.serializer(), JsonArray(rels.map { r ->
                            buildJsonObject {
                                put("sourceId", sourceId.toString())
                                put("targetId", r.metadata.id.toString())
                                put("targetName", r.metadata.name)
                                put("relationship", r.relationship)
                                put("attributes", r.attributes ?: JsonNull)
                            }
                        }))
                    }
                    "list_inverse" -> {
                        val targetId = Uuid.parse(args.str("id"))
                        val rels = api.metadata.getRelationshipsInverse(targetId)
                        json.encodeToString(JsonArray.serializer(), JsonArray(rels.map { r ->
                            buildJsonObject {
                                put("sourceId", r.metadata.id.toString())
                                put("sourceName", r.metadata.name)
                                put("targetId", targetId.toString())
                                put("relationship", r.relationship)
                                put("attributes", r.attributes ?: JsonNull)
                            }
                        }))
                    }
                    "add" -> {
                        api.metadata.addRelationship(MetadataRelationshipInput(
                            id1 = Uuid.parse(args.str("id1")),
                            id2 = Uuid.parse(args.str("id2")),
                            relationship = args.optStr("relationship"),
                            attributes = args.optJsonObject("attributes") ?: JsonObject(emptyMap()),
                        ))
                        buildJsonObject {
                            put("id1", args.str("id1")); put("id2", args.str("id2"))
                            put("relationship", args.optStr("relationship") ?: ""); put("status", "created")
                        }.toString()
                    }
                    "remove" -> {
                        api.metadata.removeRelationship(
                            Uuid.parse(args.str("id1")),
                            Uuid.parse(args.str("id2")),
                            args.str("relationship"),
                        )
                        """{"removed": true}"""
                    }
                    "merge_attributes" -> {
                        api.metadata.mergeRelationshipAttributes(
                            Uuid.parse(args.str("id1")),
                            Uuid.parse(args.str("id2")),
                            args.str("relationship"),
                            args["attributes"] ?: error("Provide attributes"),
                        )
                        buildJsonObject {
                            put("id1", args.str("id1")); put("id2", args.str("id2"))
                            put("status", "attributes_merged")
                        }.toString()
                    }
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    private fun Map<String, JsonElement>.str(key: String): String =
        get(key)?.jsonPrimitive?.content ?: error("Missing required field: $key")

    private fun Map<String, JsonElement>.optStr(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull

    private fun Map<String, JsonElement>.optInt(key: String): Int? =
        get(key)?.jsonPrimitive?.intOrNull

    private fun Map<String, JsonElement>.optBool(key: String): Boolean? =
        get(key)?.jsonPrimitive?.booleanOrNull

    private fun Map<String, JsonElement>.optJsonObject(key: String): JsonObject? =
        get(key) as? JsonObject

    private fun Map<String, JsonElement>.optJsonArray(key: String): JsonArray? =
        get(key) as? JsonArray

    private fun Map<String, JsonElement>.optStringArray(key: String): List<String>? =
        optJsonArray(key)?.map { it.jsonPrimitive.content }

    private fun JsonObject.str(key: String): String =
        get(key)?.jsonPrimitive?.content ?: error("Missing required field: $key")

    private fun JsonObject.optStr(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.toTemplateAttributeInput() = TemplateAttributeInput(
        key = str("key"),
        name = str("name"),
        description = optStr("description") ?: "",
        type = str("type").toAttributeType(),
        ui = str("ui").toAttributeUiType(),
        list = get("list")?.jsonPrimitive?.booleanOrNull ?: false,
        location = optStr("location")?.toAttributeLocation(),
        configuration = get("configuration") as? JsonObject,
    )

    private fun JsonObject.toDocumentContainerInput() = DocumentTemplateContainerInput(
        id = str("id"),
        name = str("name"),
        description = optStr("description") ?: "",
        containerType = optStr("type")?.toContainerType(),
        supplementaryKey = optStr("supplementaryKey"),
        workflows = emptyList(),
        filters = (get("filters") as? JsonArray)?.map { it.jsonPrimitive.content },
    )

    private fun File.detectContentType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        else -> "application/octet-stream"
    }

    // Fragment → JSON mappers

    // The per-operation Metadata types share no common interface with fields, so these build the JSON from
    // explicit values — callers extract them from whichever concrete selection they hold.
    private fun metadataSummaryJson(
        id: String, name: String, contentType: String, languageTag: String, version: Int, workflowState: String,
    ) = buildJsonObject {
        put("id", id); put("name", name)
        put("contentType", contentType); put("languageTag", languageTag)
        put("version", version)
        put("workflowState", workflowState)
    }

    private fun metadataDetailJson(
        id: String, name: String, contentType: String, languageTag: String, version: Int,
        attributes: JsonElement?, labels: List<String>, public: Boolean, publicContent: Boolean,
        publicSupplementary: Boolean, workflowState: String, created: String, modified: String,
    ) = buildJsonObject {
        put("id", id); put("name", name)
        put("contentType", contentType); put("languageTag", languageTag)
        put("version", version)
        put("attributes", attributes ?: JsonNull)
        put("labels", JsonArray(labels.map { JsonPrimitive(it) }))
        put("public", public)
        put("publicContent", publicContent)
        put("publicSupplementary", publicSupplementary)
        put("workflowState", workflowState)
        put("created", created)
        put("modified", modified)
    }
}
