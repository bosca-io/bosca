package bosca.git.ui

import bosca.server.BoscaApplication
import bosca.server.ContentType

/** Test-only file delivery for direct rendering and the production host’s API upstream. */
internal fun BoscaApplication.installGitUiFixtureFiles() {
    router.get("/owner/repo/raw/{ref}/{path...}") {
        if (call.request.cookies["_git_test"] != "ui-test-token")
            call.respond(bosca.server.HttpStatusCode.Forbidden)
        else if (call.pathParameters["path"] != "images/logo.png")
            call.respond(bosca.server.HttpStatusCode.NotFound)
        else
            call.respondBytes(
                java.util.Base64.getDecoder()
                    .decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1sAAAAASUVORK5CYII="
                    ),
                ContentType.Image.Png,
            )
    }
    router.get("/{owner}/{repo}") { call.respond("clone URL fallback") }
}
