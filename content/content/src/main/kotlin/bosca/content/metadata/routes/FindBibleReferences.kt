package bosca.content.metadata.routes

import bosca.bible.Reference
import bosca.content.find.FindQueryInput
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

@RouteController("/api/v1/content/metadata/bible/{id}/find")
class FindBibleReferences(
    private val metadataService: MetadataService,
    private val bibleService: BibleService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
) : Route<List<ChapterContent>>() {

    override fun serializer(): KSerializer<List<ChapterContent>> = ListSerializer(ChapterContent.serializer())

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): List<ChapterContent> {
        val id = UUID.parse(call.pathParameters["id"] ?: error("missing id"))
        val human = call.request.queryParameters["human"] ?: error("missing human")
        val content = (call.request.queryParameters["content"] ?: "true") == "true"
        val metadata = metadataService.getById(id) ?: metadataService.find(
            FindQueryInput(
                contentTypes = listOf("bosca/v-bible"),
            )
        ).firstOrNull() ?: error("Metadata not found")
        metadataPermissionEvaluator.verifyAllowed(authenticationContext, metadata, PermissionAction.VIEW)
        val bible = bibleService.getBible(metadata.id, metadata.version, null) ?: error("Bible not found")
        val reference = Reference(bibleService.getReferences(bible, human).joinToString("+") { it.usfm })
        val verses = GetBibleBookChapterVerses.getChapterVerses(reference, bible, bibleService)
        if (!content) {
            return verses.map {
                ChapterContent(
                    it.human,
                    it.humanLong,
                    it.usfm,
                    it.chapter,
                    emptyList()
                )
            }
        }
        return verses
    }
}