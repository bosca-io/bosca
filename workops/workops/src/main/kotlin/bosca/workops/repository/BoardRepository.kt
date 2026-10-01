package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.board.Board
import bosca.workops.model.board.BoardColumn
import bosca.workops.model.board.BoardProject
import bosca.workops.model.board.BoardType
import bosca.workops.model.board.SwimlaneStrategy

/** Persists [Board] and [BoardColumn] rows (R8). */
@Repository
interface BoardRepository {

    @Query("select * from workops.board where id = :id")
    suspend fun getById(id: UUID): Board?

    @Query("select * from workops.board where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Board>

    @Query("select * from workops.board where project_id = :projectId order by name")
    suspend fun listByProject(projectId: UUID): List<Board>

    @Query("select * from workops.board where program_id = :programId order by name")
    suspend fun listByProgram(programId: UUID): List<Board>

    @Query("select * from workops.board where portfolio_id = :portfolioId order by name")
    suspend fun listByPortfolio(portfolioId: UUID): List<Board>

    @Query(
        """
        select distinct b.* from workops.board b
        join workops.board_project bp on bp.board_id = b.id
        where bp.project_id = any(:projectIds)
          and b.project_id is null
          and b.program_id is null
          and b.portfolio_id is null
        order by b.name
        """
    )
    suspend fun listMultiProjectByProjectIds(projectIds: List<UUID>): List<Board>

    @Query(
        """
        insert into workops.board (project_id, program_id, portfolio_id, name, type, swimlane_strategy)
        values (:projectId, :programId, :portfolioId, :name, (:type)::workops.board_type, (:swimlaneStrategy)::workops.swimlane_strategy)
        returning *
        """
    )
    suspend fun add(
        projectId: UUID?,
        programId: UUID?,
        portfolioId: UUID?,
        name: String,
        type: BoardType,
        swimlaneStrategy: SwimlaneStrategy,
    ): Board

    // -- board_project join table --

    @Query("select * from workops.board_project where board_id = :boardId order by added_at")
    suspend fun listBoardProjects(boardId: UUID): List<BoardProject>

    @Query(
        """
        insert into workops.board_project (board_id, project_id)
        values (:boardId, :projectId)
        on conflict do nothing
        returning *
        """
    )
    suspend fun addBoardProject(boardId: UUID, projectId: UUID): BoardProject?

    @Query("delete from workops.board_project where board_id = :boardId and project_id = :projectId")
    suspend fun removeBoardProject(boardId: UUID, projectId: UUID)

    @Query("select * from workops.board_column where board_id = :boardId order by display_order")
    suspend fun listColumns(boardId: UUID): List<BoardColumn>

    @Query("select * from workops.board_column where id = :id")
    suspend fun getColumnById(id: UUID): BoardColumn?

    @Query(
        """
        insert into workops.board_column (board_id, name, display_order, status_ids, wip_limit)
        values (:boardId, :name, :displayOrder, :statusIds, :wipLimit)
        returning *
        """
    )
    suspend fun addColumn(column: BoardColumn): BoardColumn

    @Query(
        """
        update workops.board
        set name = :name,
            type = (:type)::workops.board_type,
            swimlane_strategy = (:swimlaneStrategy)::workops.swimlane_strategy,
            modified_at = now(),
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun update(
        id: UUID,
        name: String,
        type: BoardType,
        swimlaneStrategy: SwimlaneStrategy,
        expectedVersion: Long,
    ): Board?

    @Query(
        """
        update workops.board_column
        set name = :name,
            display_order = :displayOrder,
            status_ids = :statusIds,
            wip_limit = :wipLimit
        where id = :id
        returning *
        """
    )
    suspend fun updateColumn(column: BoardColumn): BoardColumn?

    @Query("delete from workops.board_column where id = :id")
    suspend fun deleteColumn(id: UUID)

    @Query("delete from workops.board where id = :id")
    suspend fun deleteById(id: UUID)
}
