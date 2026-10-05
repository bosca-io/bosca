package bosca.cli.data

import bosca.cli.BoscaCliCommand
import bosca.cli.api.ContentApi
import bosca.cli.data.ContentConverters.toCollectionType
import bosca.graphql.gen.CollectionInput
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid

class CollectionCommand : BoscaCliCommand(name = "collection") {
    override fun help(context: Context) = "Manage content collections"
    override fun run() = Unit
}

class CollectionListCommand : DataSubcommand("list") {
    override fun help(context: Context) = "List collections"

    private val offset by option("--offset").int().default(0)
    private val limit by option("--limit").int().default(20)

    override suspend fun execute(api: ContentApi) {
        val items = api.collections.getAll(offset, limit)
        for (c in items) {
            echo("${c.id}  ${c.name}")
        }
        if (items.isEmpty()) echo("No collections found.")
    }
}

class CollectionGetCommand : DataSubcommand("get") {
    override fun help(context: Context) = "Get collection details"

    private val id by option("--id", help = "Collection UUID").required()

    override suspend fun execute(api: ContentApi) {
        val c = api.collections.get(Uuid.parse(id)) ?: error("Collection not found: $id")
        echo("Collection: ${c.name}")
        echo("  ID: ${c.id}")
        echo("  Public: ${c.`public`}")
        echo("  Public List: ${c.publicList}")
        echo("  Attributes: ${c.attributes}")
    }
}

class CollectionCreateCommand : DataSubcommand("create") {
    override fun help(context: Context) = "Create a collection"

    private val name by option("--name", help = "Collection name").required()
    private val description by option("--description")
    private val slug by option("--slug")
    private val collectionType by option("--type", help = "STANDARD, FOLDER, ROOT, QUEUE, SYSTEM").default("STANDARD")
    private val parentCollectionId by option("--parent-collection-id")
    private val templateId by option("--template-id", help = "Collection template metadata UUID")
    private val templateVersion by option("--template-version").int()
    private val attributesJson by option("--attributes-json", help = "Attributes as JSON string")

    override suspend fun execute(api: ContentApi) {
        val json = Json { ignoreUnknownKeys = true }
        val collectionInput = CollectionInput(
            name = name,
            description = description,
            slug = slug,
            collectionType = collectionType.toCollectionType(),
            parentCollectionId = parentCollectionId,
            templateMetadataId = templateId?.let { Uuid.parse(it) },
            templateMetadataVersion = templateVersion,
            attributes = attributesJson?.let { json.decodeFromString<JsonElement>(it) },
        )
        val id = api.collections.add(collectionInput) ?: error("Failed to create collection")
        api.collections.setReady(id)
        echo("Created collection: $id")
    }
}

class CollectionEditCommand : DataSubcommand("edit") {
    override fun help(context: Context) = "Edit a collection"

    private val id by option("--id", help = "Collection UUID").required()
    private val name by option("--name")
    private val description by option("--description")
    private val attributesJson by option("--attributes-json")

    override suspend fun execute(api: ContentApi) {
        val uuid = Uuid.parse(id)
        val existing = api.collections.get(uuid) ?: error("Collection not found: $id")
        val json = Json { ignoreUnknownKeys = true }
        val collectionInput = CollectionInput(
            name = name ?: existing.name,
            description = description,
            attributes = attributesJson?.let { json.decodeFromString<JsonElement>(it) },
        )
        api.collections.edit(uuid, collectionInput) ?: error("Failed to edit collection: $id")
        echo("Updated collection: $id")
    }
}

class CollectionDeleteCommand : DataSubcommand("delete") {
    override fun help(context: Context) = "Delete a collection"

    private val id by option("--id", help = "Collection UUID").required()

    override suspend fun execute(api: ContentApi) {
        api.collections.delete(Uuid.parse(id))
        echo("Deleted: $id")
    }
}

class CollectionListItemsCommand : DataSubcommand("list-items") {
    override fun help(context: Context) = "List items in a collection"

    private val id by option("--id", help = "Collection UUID").required()

    override suspend fun execute(api: ContentApi) {
        val result = api.collections.list(Uuid.parse(id)) ?: error("Collection not found: $id")
        echo("Collection: ${result.collection.name}")
        for (item in result.items) {
            val type = if (item.isCollection) "COLLECTION" else "METADATA"
            echo("  ${item.id}  ${type.padEnd(12)}  ${item.name}")
        }
        if (result.items.isEmpty()) echo("  (empty)")
    }
}

class CollectionAddItemCommand : DataSubcommand("add-item") {
    override fun help(context: Context) = "Add a metadata item to a collection"

    private val id by option("--id", help = "Collection UUID").required()
    private val metadataId by option("--metadata-id", help = "Metadata UUID to add").required()

    override suspend fun execute(api: ContentApi) {
        api.collections.addMetadata(Uuid.parse(id), Uuid.parse(metadataId))
        echo("Added $metadataId to collection $id")
    }
}

class CollectionRemoveItemCommand : DataSubcommand("remove-item") {
    override fun help(context: Context) = "Remove an item from a collection"

    private val id by option("--id", help = "Collection UUID").required()
    private val metadataId by option("--metadata-id", help = "Metadata UUID to remove")
    private val childCollectionId by option("--child-collection-id", help = "Child collection UUID to remove")

    override suspend fun execute(api: ContentApi) {
        when {
            metadataId != null -> {
                api.collections.removeMetadata(Uuid.parse(id), Uuid.parse(metadataId!!))
                echo("Removed metadata $metadataId from collection $id")
            }
            childCollectionId != null -> {
                api.collections.removeCollection(Uuid.parse(id), Uuid.parse(childCollectionId!!))
                echo("Removed child collection $childCollectionId from collection $id")
            }
            else -> error("Provide --metadata-id or --child-collection-id")
        }
    }
}

class CollectionSetReadyCommand : DataSubcommand("set-ready") {
    override fun help(context: Context) = "Mark a collection as ready"

    private val id by option("--id", help = "Collection UUID").required()

    override suspend fun execute(api: ContentApi) {
        api.collections.setReady(Uuid.parse(id))
        echo("Ready: $id")
    }
}
