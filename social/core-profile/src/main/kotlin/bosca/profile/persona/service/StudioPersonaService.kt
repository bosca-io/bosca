package bosca.profile.persona.service

import bosca.profile.persona.model.StudioPersona
import bosca.profile.persona.model.StudioPersonaInput
import bosca.serialization.UUID
import bosca.service.Service

/** Service for managing Studio persona definitions and profile assignments. */
interface StudioPersonaService : Service {

    /** Retrieves all persona definitions. */
    suspend fun getAll(): List<StudioPersona>

    /** Retrieves a persona by its unique identifier. */
    suspend fun get(id: UUID): StudioPersona?

    /** Retrieves all personas assigned to a profile. */
    suspend fun getByProfile(profileId: UUID): List<StudioPersona>

    /** Creates a new persona definition. */
    suspend fun create(input: StudioPersonaInput): StudioPersona

    /** Updates an existing persona definition. */
    suspend fun update(input: StudioPersonaInput): StudioPersona

    /** Deletes a persona definition and all its profile assignments. */
    suspend fun delete(id: UUID)

    /** Assigns a persona to a profile. */
    suspend fun assignToProfile(personaId: UUID, profileId: UUID)

    /** Removes a persona assignment from a profile. */
    suspend fun removeFromProfile(personaId: UUID, profileId: UUID)
}
