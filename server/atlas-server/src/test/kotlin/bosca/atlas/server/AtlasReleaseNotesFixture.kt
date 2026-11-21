package bosca.atlas.server

import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.di.provide
import bosca.di.provideProvider
import bosca.analytics.livesessions.LiveSessionsService
import bosca.workops.model.release.LocalizedReleaseNotes
import bosca.workops.model.release.ReleaseNotesGenerationInput
import bosca.workops.service.ReleaseNotesAIService

/** Runs release acceptance with real WorkOps services and isolated external dependencies. */
@OptIn(InternalDI::class)
fun main(args: Array<String>) = startAtlasServer(args) {
    System.getenv("ATLAS_STORE_VENDOR_URL")?.let(::configureAtlasStoreFixture)
    val actualSessions = provideProvider<LiveSessionsService>()
    provides<AtlasLiveSessionsFixture>(singleton = true) { AtlasLiveSessionsFixture(actualSessions.get()) }
    provides<LiveSessionsService>(overrideExisting = true) { provide<AtlasLiveSessionsFixture>() }
    routing {
        authenticate(null, "session", "basic", "api_token") {
            post("/api/_fixture/release-notes") { AtlasReleaseNotesFixtureRoute(provide(), provide(), provide()).execute(call) }
            post("/api/_fixture/release-health") { AtlasReleaseHealthFixtureRoute(provide(), provide(), provide()).execute(call) }
            post("/api/_fixture/release-live") { AtlasReleaseLiveFixtureRoute(provide(), provide()).execute(call) }
        }
    }
    provides<ReleaseNotesAIService>(overrideExisting = true) {
        object : ReleaseNotesAIService {
            override suspend fun generate(input: ReleaseNotesGenerationInput): LocalizedReleaseNotes {
                check(input.commits.any { "Improve release screen" in it.message }) { "Fixture requires actual tagged Git evidence." }
                return LocalizedReleaseNotes(input.sourceLocale, "Release screen improvements", "Updated release screen", "Test the release screen")
            }
        }
    }
}
