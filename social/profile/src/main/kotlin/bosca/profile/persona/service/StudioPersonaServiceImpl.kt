package bosca.profile.persona.service

import bosca.profile.persona.model.StudioPersona
import bosca.profile.persona.model.StudioPersonaInput
import bosca.profile.persona.repository.StudioPersonaRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/** Default implementation of [StudioPersonaService] backed by [StudioPersonaRepository]. */
@ServiceImplementation
class StudioPersonaServiceImpl(
    private val repository: StudioPersonaRepository,
) : StudioPersonaService {

    override suspend fun getAll(): List<StudioPersona> = repository.getAll()

    override suspend fun get(id: UUID): StudioPersona? = repository.get(id)

    override suspend fun getByProfile(profileId: UUID): List<StudioPersona> = repository.getByProfile(profileId)

    override suspend fun create(input: StudioPersonaInput): StudioPersona = repository.create(input)

    override suspend fun update(input: StudioPersonaInput): StudioPersona = repository.update(input)

    override suspend fun delete(id: UUID) = repository.delete(id)

    override suspend fun assignToProfile(personaId: UUID, profileId: UUID) =
        repository.assignToProfile(personaId, profileId)

    override suspend fun removeFromProfile(personaId: UUID, profileId: UUID) =
        repository.removeFromProfile(personaId, profileId)
}
