package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable

@Serializable
class SystemPersonasModel(
    val id: String = "", val admin: Boolean = false,
    var personas: List<SystemPersonasData.Profiles.StudioPersonas.All> = emptyList(),
    var persona: SystemPersonaData.Profiles.StudioPersonas.Persona? = null,
    var draft: SystemPersonaDraft = SystemPersonaDraft(), val feedback: SystemFeedback = SystemFeedback(),
    var editorRequest: Long = 0, var assignmentRequest: Long = 0,
) {
    val title: String get() = persona?.name ?: "Personas"
    val editorOpenRequest: String get() = if (editorRequest > 0) "persona-$editorRequest" else ""
    val assignmentOpenRequest: String get() = if (feedback.failed && assignmentRequest > 0) "assignment-$assignmentRequest" else ""
    val subsystemOptions: Map<String, String> get() = systemPersonaSubsystems + draft.subsystems.filterNot { it in systemPersonaSubsystems }.associateWith { it }
    val dirty: Boolean get() = persona?.let { draft != SystemPersonaDraft(it.name, it.description.orEmpty(), it.subsystemIds, it.enabled) } ?: draft.name.isNotBlank()
    suspend fun reload(ctx: RenderContext) {
        if (!admin) return
        if (id.isBlank()) personas = ctx.gql.execute(SystemPersonas, Unit).profiles.studioPersonas.all
        else {
            persona = ctx.gql.execute(SystemPersona, SystemPersona.Variables(id)).profiles.studioPersonas.persona
            val current = persona
            if (current == null) ctx.notFound = true
            else draft = SystemPersonaDraft(current.name, current.description.orEmpty(), current.subsystemIds, current.enabled)
        }
    }
    fun selectAll(name: String, description: String, enabled: Boolean) {
        assignmentRequest = 0
        draft = SystemPersonaDraft(name, description, systemPersonaSubsystems.keys.toList(), enabled)
        editorRequest++
    }
    suspend fun save(name: String, description: String, subsystemIds: String, enabled: Boolean, ctx: RenderContext) = feedback.perform {
        assignmentRequest = 0
        editorRequest++
        require(admin) { "Administrator access required." }
        draft = SystemPersonaDraft(name, description, systemChoices(subsystemIds), enabled)
        require(name.isNotBlank()) { "Enter a persona name." }
        val input = StudioPersonaInput(id = id.takeIf(String::isNotBlank), name = name.trim(), description = description.takeIf(String::isNotBlank), subsystemIds = draft.subsystems, enabled = enabled)
        if (id.isBlank()) {
            val created = ctx.gql.execute(SystemCreatePersona, SystemCreatePersona.Variables(input)).profiles.studioPersonas.create
            feedback.nextHref = "/system/security/personas/${systemEncode(created.id)}"
        } else {
            ctx.gql.execute(SystemUpdatePersona, SystemUpdatePersona.Variables(input))
            reload(ctx)
        }
        feedback.message = "Persona saved."
    }
    suspend fun delete(personaId: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        assignmentRequest = 0
        require(admin && confirmed) { "Confirm persona deletion." }
        ctx.gql.execute(SystemDeletePersona, SystemDeletePersona.Variables(personaId))
        if (id.isNotBlank()) feedback.nextHref = "/system/security/personas" else reload(ctx)
        feedback.message = "Persona deleted."
    }
    suspend fun assign(profileId: String, ctx: RenderContext) = feedback.perform {
        assignmentRequest++
        require(admin) { "Administrator access required." }
        require(profileId.isNotBlank()) { "Enter a profile ID." }
        check(ctx.gql.execute(SystemAssignPersona, SystemAssignPersona.Variables(id, profileId.trim())).profiles.studioPersonas.assignToProfile) { "Persona was not assigned." }
        feedback.message = "Persona assigned to profile."
    }
}

suspend fun systemPersonas(): SystemPersonasModel {
    val ctx = currentRenderContext()
    return SystemPersonasModel(ctx.params["id"].orEmpty(), systemAdministrator(ctx)).also { model -> model.feedback.perform { model.reload(ctx) } }
}
