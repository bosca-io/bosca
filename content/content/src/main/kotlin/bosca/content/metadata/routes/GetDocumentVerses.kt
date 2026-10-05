package bosca.content.metadata.routes

import bosca.bible.Reference
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.documents.ContainerNode
import bosca.documents.Content
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

@RouteController("/api/v1/content/metadata/{id}/document/verses")
class GetDocumentVerses(
    metadataService: MetadataService,
    bibleService: BibleService,
    metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val documentService: DocumentService,
    private val json: Json,
) : BaseBibleRoute<List<ChapterContent>>(metadataService, metadataPermissionEvaluator, bibleService) {

    override fun serializer(): KSerializer<List<ChapterContent>> = ListSerializer(ChapterContent.serializer())

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): List<ChapterContent> {
        val id = UUID.parse(call.pathParameters["id"] ?: error("missing id"))
        val verses = getVerses(call, id, authenticationContext)
        return verses
    }

    private suspend fun getVerses(call: ServerCall, id: UUID, authenticationContext: AuthenticationContext): List<ChapterContent> {
        val metadata = metadataService.getById(id) ?: return emptyList()
        val document = documentService.getDocument(metadata.id, metadata.version)
        val bible = call.getBibleByLanguage(authenticationContext)
        document?.content?.let { content ->
            content.document.content.forEach { node ->
                if (node is ContainerNode && node.attributes.name == "BIBLE_REFERENCES") {
                    val usfm = node.attributes.references?.joinToString("+")
                    val reference = Reference(usfm ?: return@forEach)
                    return GetBibleBookChapterVerses.getChapterVerses(reference, bible, bibleService)
                }
            }
        }
        metadata.parentId?.let {
            return getVerses(call, it, authenticationContext)
        }
        return emptyList()
    }
}