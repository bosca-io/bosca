package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.label.Label
import bosca.workops.model.label.LabelScope

/** Persists [Label] rows (R9). */
@Repository
interface LabelRepository {

    @Query("select * from workops.label where id = :id")
    suspend fun getById(id: UUID): Label?

    @Query("select * from workops.label where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Label>

    @Query("select * from workops.label where scope = ('global')::workops.label_scope order by name")
    suspend fun listGlobal(): List<Label>

    @Query("select * from workops.label where portfolio_id = :portfolioId order by name")
    suspend fun listByPortfolio(portfolioId: UUID): List<Label>

    @Query("select * from workops.label where program_id = :programId order by name")
    suspend fun listByProgram(programId: UUID): List<Label>

    @Query("select * from workops.label where project_id = :projectId order by name")
    suspend fun listByProject(projectId: UUID): List<Label>

    @Query(
        """
        insert into workops.label (name, color_hex, scope, portfolio_id, program_id, project_id)
        values (:name, :colorHex, (:scope)::workops.label_scope, :portfolioId, :programId, :projectId)
        returning *
        """
    )
    suspend fun add(
        name: String,
        colorHex: String?,
        scope: LabelScope,
        portfolioId: UUID?,
        programId: UUID?,
        projectId: UUID?,
    ): Label

    @Query(
        """
        update workops.label
        set name = :name, color_hex = :colorHex, version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun update(id: UUID, name: String, colorHex: String?, expectedVersion: Long): Label?

    @Query("delete from workops.label where id = :id")
    suspend fun deleteById(id: UUID)
}
