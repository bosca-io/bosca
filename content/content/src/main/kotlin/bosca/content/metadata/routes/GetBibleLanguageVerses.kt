package bosca.content.metadata.routes

import bosca.bible.Reference
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

@RouteController("/api/v1/content/metadata/bible/contents")
class GetBibleLanguageVerses(
    metadataService: MetadataService,
    metadataPermissionEvaluator: MetadataPermissionEvaluator,
    bibleService: BibleService,
) : BaseBibleRoute<List<ChapterContent>>(metadataService, metadataPermissionEvaluator, bibleService) {

    override fun serializer(): KSerializer<List<ChapterContent>> = ListSerializer(ChapterContent.serializer())

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): List<ChapterContent> {
        val bible = call.getBibleByLanguage(authenticationContext)
        val reference = Reference(call.request.queryParameters["usfm"]?.replace(' ', '+') ?: error("missing usfm"))
        return GetBibleBookChapterVerses.getChapterVerses(reference, bible, bibleService)
    }
}
