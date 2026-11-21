package bosca.workops.ui

import bosca.bible.Reference
import bosca.bible.bibleJson
import bosca.bible.components.IComponent
import bosca.bible.components.filter
import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.content.metadata.model.toJson
import bosca.workops.ui.generated.WorkOpsDocumentReferences
import bosca.workops.ui.graphql.WorkOpsDocumentMetadataChoices
import bosca.workops.ui.graphql.WorkOpsDocumentMetadataReference
import bosca.workops.ui.graphql.WorkOpsDocumentBibleChoices
import bosca.workops.ui.graphql.WorkOpsDocumentScriptureChoices
import bosca.workops.ui.graphql.WorkOpsDocumentScripturePassage
import kotlinx.serialization.json.JsonNull

/** Uses the existing Content/search permission boundaries for document reference controls. */
class WorkOpsDocumentReferenceLookup : WorkOpsDocumentReferences {
    override suspend fun searchMetadata(gql: GraphQLClient, query: String, filters: List<String>): List<Map<String, String>> =
        gql.execute(WorkOpsDocumentMetadataChoices, WorkOpsDocumentMetadataChoices.Variables(query, listOf("_type = \"metadata\"") + filters))
            .search.search?.documents.orEmpty().mapNotNull { it.metadata }.distinctBy { it.id }.map {
                mapOf("id" to it.id, "label" to workOpsContentLabel(it.name, it.id, "Untitled document"), "contentType" to it.content?.type.orEmpty())
            }

    override suspend fun metadata(gql: GraphQLClient, id: String, version: Int?): Map<String, String>? =
        gql.execute(WorkOpsDocumentMetadataReference, WorkOpsDocumentMetadataReference.Variables(id, version)).content.metadata?.let {
            mapOf("id" to it.id, "label" to workOpsContentLabel(it.name, it.id, "Untitled document"), "contentType" to it.content?.type.orEmpty())
        }

    override suspend fun bibles(gql: GraphQLClient, offset: Int): List<Map<String, String>> {
        require(offset >= 0) { "Invalid Bible page." }
        // Follow the existing Bible listing's visible metadata/default variant selection before UI paging.
        return gql.execute(WorkOpsDocumentBibleChoices, Unit).content.findMetadata.mapNotNull { metadata ->
            metadata.bible?.let { bible -> mapOf("id" to metadata.id, "label" to bible.name.ifBlank { metadata.name }.ifBlank { "Untitled Bible" }) }
        }.drop(offset).take(25)
    }

    override suspend fun findScripture(gql: GraphQLClient, id: String, query: String): List<Map<String, String>> {
        if (query.isBlank()) return emptyList()
        val bible = gql.execute(WorkOpsDocumentScriptureChoices, WorkOpsDocumentScriptureChoices.Variables(id, query.trim())).content.metadata?.bible
            ?: throw NoSuchElementException("Bible is unavailable.")
        val resolved = bible.find.filter { it.chapter != null }
        return buildList {
            if (resolved.isNotEmpty()) add(mapOf("id" to resolved.joinToString("+") { it.reference.usfm },
                "label" to resolved.joinToString(", ") { it.reference.human } + " (as entered)"))
            for (result in resolved) {
                val chapter = result.chapter ?: continue
                add(mapOf("id" to chapter.reference.usfm, "label" to chapter.reference.human + " (whole chapter)"))
                for (verse in chapter.verses) add(mapOf("id" to verse.usfm, "label" to verse.human))
            }
        }.distinctBy { it.getValue("id") }
    }

    override suspend fun scripture(gql: GraphQLClient, id: String, reference: String): List<Map<String, String>> {
        val requested = Reference(reference)
        val chapters = requested.references.groupBy { it.chapterUsfm }
        require(chapters.isNotEmpty() && chapters.keys.none { it.isBlank() }) { "Select a chapter or verse reference." }
        val passages = mutableListOf<Map<String, String>>()
        for ((chapterId, refs) in chapters) {
            val grouped = Reference(refs.joinToString("+") { it.usfm })
            val bible = gql.execute(WorkOpsDocumentScripturePassage,
                WorkOpsDocumentScripturePassage.Variables(id, chapterId, grouped.bookUsfm)).content.metadata?.bible
                ?: return emptyList()
            val chapter = bible.chapter ?: throw NoSuchElementException("Chapter is unavailable.")
            val source = chapter.component.takeUnless { it == JsonNull } ?: throw NoSuchElementException("Scripture is unavailable.")
            val components = if (refs.any { it.number.isEmpty() }) source else {
                bibleJson.decodeFromJsonElement(IComponent.serializer(), source).filter(grouped)?.toJson()
                    ?: throw NoSuchElementException("Scripture reference is unavailable.")
            }
            val book = bible.book ?: throw NoSuchElementException("Book is unavailable.")
            passages.add(mapOf("label" to "${bible.name} — ${grouped.toHuman(book.nameLong)}",
                "components" to components.toString(), "styles" to bible.styles.toString(),
                "copyright" to chapter.attribution?.copyright.orEmpty(), "citation" to chapter.attribution?.citation.orEmpty(),
                "informationUrl" to chapter.attribution?.informationUrl.orEmpty()))
        }
        return passages
    }
}
