package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.board.Board
import bosca.workops.model.board.BoardColumn
import bosca.workops.model.board.BoardProject
import bosca.workops.model.board.CreateBoardColumnInput
import bosca.workops.model.board.CreateBoardInput
import bosca.workops.model.board.UpdateBoardColumnInput
import bosca.workops.model.board.UpdateBoardInput
import bosca.workops.model.component.Component
import bosca.workops.model.component.CreateComponentInput
import bosca.workops.model.label.CreateLabelInput
import bosca.workops.model.label.Label
import bosca.workops.model.milestone.CreateMilestoneInput
import bosca.workops.model.milestone.Milestone
import bosca.workops.model.version.CreateVersionInput
import bosca.workops.model.version.Version

// ----- Board -----

interface BoardService : Service {
    suspend fun getById(id: UUID): Board?
    suspend fun getByIds(ids: List<UUID>): List<Board>
    suspend fun listByProject(projectId: UUID): List<Board>
    suspend fun listByProgram(programId: UUID): List<Board>
    suspend fun listByPortfolio(portfolioId: UUID): List<Board>
    suspend fun listMultiProjectByProjectIds(projectIds: List<UUID>): List<Board>
    suspend fun listBoardProjects(boardId: UUID): List<BoardProject>
    suspend fun create(input: CreateBoardInput): Board
    suspend fun update(id: UUID, input: UpdateBoardInput): Board
    suspend fun addBoardProject(boardId: UUID, projectId: UUID): BoardProject?
    suspend fun removeBoardProject(boardId: UUID, projectId: UUID)
    suspend fun addColumn(input: CreateBoardColumnInput): BoardColumn
    suspend fun updateColumn(input: UpdateBoardColumnInput): BoardColumn
    suspend fun getColumnById(columnId: UUID): BoardColumn?
    suspend fun listColumns(boardId: UUID): List<BoardColumn>
    suspend fun deleteColumn(columnId: UUID)
    suspend fun delete(boardId: UUID)
}

// ----- Version -----

interface VersionService : Service {
    suspend fun getById(id: UUID): Version?
    suspend fun getByIds(ids: List<UUID>): List<Version>
    suspend fun listByProject(projectId: UUID): List<Version>
    suspend fun create(input: CreateVersionInput): Version
    suspend fun release(id: UUID, expectedVersion: Long): Version
    suspend fun delete(id: UUID)
}

// ----- Component / Label / Milestone -----

interface ComponentService : Service {
    suspend fun listByProject(projectId: UUID): List<Component>
    suspend fun getById(id: UUID): Component?
    suspend fun getByIds(ids: List<UUID>): List<Component>
    suspend fun create(input: CreateComponentInput): Component
    suspend fun delete(id: UUID)
}

interface LabelService : Service {
    suspend fun getById(id: UUID): Label?
    suspend fun getByIds(ids: List<UUID>): List<Label>
    suspend fun listGlobal(): List<Label>
    suspend fun listByPortfolio(portfolioId: UUID): List<Label>
    suspend fun listByProgram(programId: UUID): List<Label>
    suspend fun listByProject(projectId: UUID): List<Label>
    suspend fun create(input: CreateLabelInput): Label
    suspend fun update(id: UUID, name: String, colorHex: String?, expectedVersion: Long): Label
    suspend fun delete(id: UUID)
}

interface MilestoneService : Service {
    suspend fun listByProgram(programId: UUID): List<Milestone>
    suspend fun getById(id: UUID): Milestone?
    suspend fun getByIds(ids: List<UUID>): List<Milestone>
    suspend fun create(input: CreateMilestoneInput): Milestone
    suspend fun close(id: UUID, expectedVersion: Long): Milestone
}
