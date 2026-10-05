package bosca.bml.message.server

import bosca.bml.message.client.HostedMessageProject
import bosca.bml.message.client.HostedMessageTemplate
import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * The PRIVATE project catalog (cluster-internal, like `/render`): what the server hosts and
 * which versions exist — the source the platform's authoring dropdowns enumerate.
 *
 * - `GET /projects` — every hosted project with its active version and template keys
 *   (in-memory; no registry round-trip).
 * - `GET /projects/{project}/versions` — the project's published and bundled versions, newest
 *   first. During a registry outage the bundled versions remain available as pin choices.
 */
object ProjectsApi {

    private val json = Json { ignoreUnknownKeys = true }

    fun install(
        application: BoscaApplication,
        projects: MessageProjects,
        artifacts: MessageArtifactClient,
        bundled: BundledMessageProjects = BundledMessageProjects.Empty,
    ) {
        val router = application.router

        router.get("/projects") {
            val hosted = projects.projects.sorted().mapNotNull { project ->
                val version = projects.activeVersion(project) ?: return@mapNotNull null
                val templates = projects.templateKeys(project).sorted().map { key ->
                    HostedMessageTemplate(
                        key = key,
                        samplePayload = projects.payloadSample(project, key),
                        payloadSchema = projects.payloadSchema(project, key),
                        supportsEmail = projects.supportsEmail(project, key),
                        supportsPush = projects.supportsPush(project, key),
                    )
                }
                HostedMessageProject(project, version, templates)
            }
            call.respondBytes(
                json.encodeToString(ListSerializer(HostedMessageProject.serializer()), hosted).toByteArray(Charsets.UTF_8),
                ContentType.Application.Json,
            )
        }

        router.get("/projects/{project}/versions") {
            val project = call.pathParameters["project"].orEmpty()
            if (project.isBlank() || projects.activeVersion(project) == null) {
                call.respond(HttpStatusCode.NotFound, "404 Not Found")
                return@get
            }
            val published = try {
                artifacts.versions(project)
                    .filter { it.jar != null }
                    .sortedWith(compareByDescending<MessageArtifactClient.VersionInfo> { it.version }.thenByDescending { it.created })
                    .map { it.version }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("bml-message: version listing failed for {}, returning bundled versions: {}", project, e.message)
                emptyList()
            }
            val versions = (published + bundled.versions(project).map { it.version }).distinct().sortedDescending()
            call.respondBytes(
                json.encodeToString(ListSerializer(String.serializer()), versions).toByteArray(Charsets.UTF_8),
                ContentType.Application.Json,
            )
        }
    }

    private val log = LoggerFactory.getLogger(ProjectsApi::class.java)
}
