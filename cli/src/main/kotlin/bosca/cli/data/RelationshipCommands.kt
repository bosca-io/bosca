package bosca.cli.data

import bosca.cli.BoscaCliCommand
import bosca.cli.api.ContentApi
import bosca.graphql.gen.MetadataRelationshipInput
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

class RelationshipCommand : BoscaCliCommand(name = "relationship") {
    override fun help(context: Context) = "Manage relationships between content items"
    override fun run() = Unit
}

class RelationshipListCommand : DataSubcommand("list") {
    override fun help(context: Context) = "List outgoing relationships for a metadata item"

    private val id by option("--id", help = "Metadata UUID").required()

    override suspend fun execute(api: ContentApi) {
        val rels = api.metadata.getRelationships(Uuid.parse(id))
        for (r in rels) {
            val target = r.metadata
            echo("$id -[${r.relationship}]-> ${target.id} (${target.name})")
        }
        if (rels.isEmpty()) echo("No relationships found.")
    }
}

class RelationshipListInverseCommand : DataSubcommand("list-inverse") {
    override fun help(context: Context) = "List incoming relationships for a metadata item"

    private val id by option("--id", help = "Metadata UUID").required()

    override suspend fun execute(api: ContentApi) {
        val rels = api.metadata.getRelationshipsInverse(Uuid.parse(id))
        for (r in rels) {
            val source = r.metadata
            echo("${source.id} (${source.name}) -[${r.relationship}]-> $id")
        }
        if (rels.isEmpty()) echo("No incoming relationships found.")
    }
}

class RelationshipAddCommand : DataSubcommand("add") {
    override fun help(context: Context) = "Create a relationship between two metadata items"

    private val id1 by option("--id1", help = "Source metadata UUID").required()
    private val id2 by option("--id2", help = "Target metadata UUID").required()
    private val relationship by option("--relationship", help = "Relationship type (e.g. thumbnail, author, related)").required()
    private val attributesJson by option("--attributes-json", help = "Relationship attributes as JSON")

    override suspend fun execute(api: ContentApi) {
        val json = Json { ignoreUnknownKeys = true }
        api.metadata.addRelationship(MetadataRelationshipInput(
            id1 = Uuid.parse(id1),
            id2 = Uuid.parse(id2),
            relationship = relationship,
            attributes = attributesJson?.let { json.decodeFromString<JsonElement>(it) } ?: JsonObject(emptyMap()),
        ))
        echo("Created relationship: $id1 -[$relationship]-> $id2")
    }
}

class RelationshipRemoveCommand : DataSubcommand("remove") {
    override fun help(context: Context) = "Remove a relationship between two metadata items"

    private val id1 by option("--id1", help = "Source metadata UUID").required()
    private val id2 by option("--id2", help = "Target metadata UUID").required()
    private val relationship by option("--relationship", help = "Relationship type").required()

    override suspend fun execute(api: ContentApi) {
        api.metadata.removeRelationship(Uuid.parse(id1), Uuid.parse(id2), relationship)
        echo("Removed relationship: $id1 -[$relationship]-> $id2")
    }
}
