package bosca.profile.persona.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.persona.model.StudioPersona
import bosca.profile.persona.model.StudioPersonaInput
import bosca.serialization.UUID

/** Data access for the `profiles.studio_personas` and `profiles.studio_persona_profiles` tables. */
@Repository
interface StudioPersonaRepository {

    @Query("select * from profiles.studio_personas order by name")
    suspend fun getAll(): List<StudioPersona>

    @Query("select * from profiles.studio_personas where id = :id")
    suspend fun get(id: UUID): StudioPersona?

    @Query("""
        select sp.* from profiles.studio_personas sp
        join profiles.studio_persona_profiles spp on sp.id = spp.persona_id
        where spp.profile_id = :profileId and sp.enabled = true
        order by sp.name
    """)
    suspend fun getByProfile(profileId: UUID): List<StudioPersona>

    @Query("insert into profiles.studio_personas (name, description, subsystem_ids) values (:name, :description, :subsystemIds) returning *")
    suspend fun create(input: StudioPersonaInput): StudioPersona

    @Query("update profiles.studio_personas set name = :name, description = :description, subsystem_ids = :subsystemIds, enabled = :enabled, modified = now() where id = :id returning *")
    suspend fun update(input: StudioPersonaInput): StudioPersona

    @Query("delete from profiles.studio_personas where id = :id")
    suspend fun delete(id: UUID)

    @Query("insert into profiles.studio_persona_profiles (persona_id, profile_id) values (:personaId, :profileId) on conflict do nothing")
    suspend fun assignToProfile(personaId: UUID, profileId: UUID)

    @Query("delete from profiles.studio_persona_profiles where persona_id = :personaId and profile_id = :profileId")
    suspend fun removeFromProfile(personaId: UUID, profileId: UUID)
}
