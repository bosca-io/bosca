package bosca.atlas.server

import bosca.localization.model.LocalizationProjectInput
import bosca.localization.service.LocalizationService
import bosca.languages.model.Language
import bosca.languages.service.LanguagesService
import bosca.routes.APIRoute
import bosca.security.service.GroupEvaluator
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import kotlinx.serialization.json.*

/** Configures only disposable notes fixtures through their owning service, without adding Atlas administration APIs. */
internal class AtlasReleaseNotesFixtureRoute(
    private val localization: LocalizationService,
    private val groups: GroupEvaluator,
    private val languages: LanguagesService,
) : APIRoute<JsonObject>() {
    override fun serializer() = JsonObject.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): JsonObject {
        groups.verifyHasAdminGroup(authenticationContext)
        val input = call.receive<JsonObject>()
        fun text(key: String) = input.getValue(key).jsonPrimitive.content
        return when (text("action")) {
            "create" -> {
                val project = UUID.parse(text("project"))
                if (languages.get("en") == null) languages.add(Language("en", "English", "English"))
                val created = localization.addProject(LocalizationProjectInput(
                    "Release content notes", sourceLanguage = "en",
                    attributes = buildJsonObject { put("workopsProjectId", project.toString()) },
                ))
                buildJsonObject { put("id", created.id.toString()) }
            }
            "breakRead" -> {
                val field = requireNotNull(localization.getStringByKey(UUID.parse(text("localization")),
                    "workops.release-notes.${UUID.parse(text("version"))}.play-release-notes"))
                localization.deleteString(field.id)
                buildJsonObject { put("deleted", true) }
            }
            else -> error("Unknown release notes fixture action.")
        }
    }
}
