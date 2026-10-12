@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.content.metadata.routes

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.routes.configureContentRoutes
import bosca.security.service.AuthenticationProviders
import bosca.server.BoscaApplication
import bosca.server.HttpMethod
import bosca.server.config.ApplicationConfig
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BibleRouteVersionTest {
    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `generated routes keep every v1 endpoint and add v2 for the changed chapter response`() = runTest {
        ProviderRegistry.clear()
        val application = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
        provides<AuthenticationProviders> { AuthenticationProviders(emptyArray()) }
        provides<bosca.content.collection.routes.GetCollectionBySlug> { mockk() }
        provides<bosca.content.collection.routes.GetCollectionCollaboration> { mockk() }
        provides<bosca.content.collection.routes.GetCollectionItems> { mockk() }
        provides<bosca.content.collection.routes.GetCollection> { mockk() }
        provides<bosca.content.collection.routes.SetCollectionCollaboration> { mockk() }
        provides<bosca.content.collection.routes.GetCollectionItemsBySlug> { mockk() }
        provides<bosca.content.metadata.routes.GetDocumentVerses> { mockk() }
        provides<bosca.content.metadata.routes.GetDataCollaboration> { mockk() }
        provides<bosca.content.metadata.routes.AddBible> { mockk() }
        provides<bosca.content.metadata.routes.Image> { mockk() }
        provides<bosca.content.metadata.routes.GetBibleLanguageVerses> { mockk() }
        provides<bosca.content.metadata.routes.GetBibles> { mockk() }
        provides<bosca.content.metadata.routes.FindBibleReferences> { mockk() }
        provides<bosca.content.metadata.routes.GetBibleBookChapterVerses> { mockk() }
        provides<bosca.content.metadata.routes.LanguageVariant> { mockk() }
        provides<bosca.content.metadata.routes.LanguageVariantLegacy> { mockk() }
        provides<bosca.content.metadata.routes.Upload> { mockk() }
        provides<bosca.content.metadata.routes.SetDocumentCollaboration> { mockk() }
        provides<bosca.content.metadata.routes.GetBibleBookChapter> { mockk() }
        provides<bosca.content.metadata.routes.GetDocumentCollaboration> { mockk() }
        provides<bosca.content.metadata.routes.GetBibleBooks> { mockk() }
        provides<bosca.content.metadata.routes.DocumentCollaborationAccess> { mockk() }
        provides<bosca.content.metadata.routes.DownloadFile> { mockk() }
        provides<bosca.content.metadata.routes.SetDataCollaboration> { mockk() }
        provides<bosca.content.metadata.routes.GetBibleBookChapters> { mockk() }
        provides<bosca.content.metadata.routes.DeleteBible> { mockk() }
        provides<bosca.content.metadata.routes.SetDocument> { mockk() }
        provides<bosca.content.metadata.routes.GetBible> { mockk() }
        provides<bosca.content.metadata.routes.Download> { mockk() }
        provides<GetBibleBookChapterV2> { mockk() }
        provides<GetBibleAudio> { mockk() }
        application.configureContentRoutes()

        val paths = listOf(
            "/content/metadata/bible/{id}/books/{usfm}",
            "/content/metadata/bible/{id}/books/{bookUsfm}/chapter/{chapterUsfm}",
            "/content/metadata/bible/{id}/contents",
            "/content/metadata/bible/contents",
            "/content/metadata/bible/{id}/find",
            "/content/metadata/{id}/document/verses",
        )
        val registered = application.router.collectRouteDescriptions().filter { it.method == "GET" }.map { it.path }
        for (path in paths) {
            val pattern = "/api/v1$path"
            assertTrue(pattern in registered, "Missing GET $pattern")
            val requestPath = pattern.replace("{id}", "00000000-0000-0000-0000-000000000001")
                .replace("{usfm}", "GEN").replace("{bookUsfm}", "GEN").replace("{chapterUsfm}", "GEN.1")
            assertNotNull(application.router.resolve(HttpMethod.Get, requestPath), requestPath)
        }
        val chapterV2 = "/api/v2/content/metadata/bible/{id}/books/{bookUsfm}/chapter/{chapterUsfm}"
        assertTrue(chapterV2 in registered)
        assertNotNull(application.router.resolve(HttpMethod.Get,
            "/api/v2/content/metadata/bible/00000000-0000-0000-0000-000000000001/books/GEN/chapter/GEN.1"))
        assertTrue("/api/v1/content/metadata/bible/audio" in registered)
        assertNotNull(application.router.resolve(HttpMethod.Get, "/api/v1/content/metadata/bible/audio"))
    }
}
