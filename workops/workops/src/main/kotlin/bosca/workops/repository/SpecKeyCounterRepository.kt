package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface SpecKeyCounterRepository {

    @Query("insert into workops.spec_key_counter (project_id, next_number) values (:projectId, 1)")
    suspend fun initialize(projectId: UUID)

    @Query(
        """
        update workops.spec_key_counter
        set next_number = next_number + 1
        where project_id = :projectId
        returning next_number - 1
        """
    )
    suspend fun reserveNext(projectId: UUID): Long?
}

@Repository
interface RequirementKeyCounterRepository {

    @Query("insert into workops.requirement_key_counter (project_id, next_number) values (:projectId, 1)")
    suspend fun initialize(projectId: UUID)

    @Query(
        """
        update workops.requirement_key_counter
        set next_number = next_number + 1
        where project_id = :projectId
        returning next_number - 1
        """
    )
    suspend fun reserveNext(projectId: UUID): Long?
}
