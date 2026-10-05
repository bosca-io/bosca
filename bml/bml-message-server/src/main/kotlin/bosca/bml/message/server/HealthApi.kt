package bosca.bml.message.server

import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Health endpoints matching bosca-server's contract (`InitializeModule` + the helm probes):
 *
 * - `GET /api/v1/live` — liveness: "is the process wedged?", nothing more. Touches NO
 *   dependencies, so a slow registry can never trigger a restart storm.
 * - `GET /api/v1/ready` — readiness: 200 once warmup completed (the server can serve; a
 *   project-level failure degrades that project only), 503 while still warming.
 * - `GET /api/v1/health` — startup probe + diagnostics: the hosted projects with their active
 *   versions and template keys, plus `ok`.
 *
 * Internal like the render API — deployments must not route these publicly.
 */
object HealthApi {

    @Serializable
    data class StatusBody(val status: String)

    @Serializable
    data class HealthBody(val projects: List<ProjectHealth>, val ok: Boolean) {
        @Serializable
        data class ProjectHealth(val project: String, val activeVersion: String?, val templates: List<String>)
    }

    private val json = Json

    fun install(application: BoscaApplication, projects: MessageProjects, reloader: MessageReloader) {
        val router = application.router

        router.get("/api/v1/live") {
            call.respondBytes(
                json.encodeToString(StatusBody.serializer(), StatusBody("live")).toByteArray(Charsets.UTF_8),
                ContentType.Application.Json,
            )
        }

        router.get("/api/v1/ready") {
            if (reloader.warmedUp) {
                call.respondBytes(
                    json.encodeToString(StatusBody.serializer(), StatusBody("ready")).toByteArray(Charsets.UTF_8),
                    ContentType.Application.Json,
                )
            } else {
                call.respondBytes(
                    json.encodeToString(StatusBody.serializer(), StatusBody("warming up")).toByteArray(Charsets.UTF_8),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            }
        }

        router.get("/api/v1/health") {
            val body = HealthBody(
                projects = projects.projects.sorted().map {
                    HealthBody.ProjectHealth(it, projects.activeVersion(it), projects.templateKeys(it).sorted())
                },
                ok = reloader.warmedUp,
            )
            call.respondBytes(
                json.encodeToString(HealthBody.serializer(), body).toByteArray(Charsets.UTF_8),
                ContentType.Application.Json,
                if (reloader.warmedUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            )
        }
    }
}
