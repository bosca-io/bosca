package bosca.workops.model.board

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@DbMapper(BoardTypeMapper::class)
@Serializable
enum class BoardType {
    KANBAN,
    SCRUM,
}

object BoardTypeMapper : EnumMapper<BoardType>({ BoardType.valueOf(it.uppercase()) })

@DbMapper(SwimlaneStrategyMapper::class)
@Serializable
enum class SwimlaneStrategy {
    NONE,
    ASSIGNEE,
    EPIC,
    PRIORITY,
    PROJECT,
    QUERY,
}

object SwimlaneStrategyMapper : EnumMapper<SwimlaneStrategy>({ SwimlaneStrategy.valueOf(it.uppercase()) })

/**
 * One column on a [Board] (R8). Each column collects tasks whose
 * status is in [statusIds]; dragging a card across columns issues
 * the workflow transition that lands the card's task in one of the
 * target column's statuses (R8: boards never bypass the workflow).
 *
 * The optional [wipLimit] is enforced per the project's
 * `enforceCapacity` flag — soft warning by default.
 */
@Serializable
data class BoardColumn(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("board_id")
    @Contextual
    val boardId: UUID,
    val name: String,
    @ColumnName("display_order")
    val displayOrder: Int,
    @ColumnName("status_ids")
    val statusIds: List<@Contextual UUID>,
    @ColumnName("wip_limit")
    val wipLimit: Int? = null,
)

/**
 * A configurable view onto tasks from one or more projects.
 * A board projects task rows through its [filterId]
 * (saved filter), groups the result into columns by status, and
 * optionally splits into swimlanes per [swimlaneStrategy].
 *
 * Scope options (at most one parent set; all NULL = multi-project):
 * - [projectId] set: single-project board
 * - [programId] set: program-scoped board (all projects in program)
 * - [portfolioId] set: portfolio-scoped board
 * - all NULL: standalone board; scope from [BoardProject] join rows
 */
@BatchKey("id")
@Serializable
data class Board(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID? = null,
    @ColumnName("program_id")
    @Contextual
    val programId: UUID? = null,
    @ColumnName("portfolio_id")
    @Contextual
    val portfolioId: UUID? = null,
    val name: String,
    val type: BoardType,
    @ColumnName("filter_id")
    @Contextual
    val filterId: UUID? = null,
    @ColumnName("sub_filter_id")
    @Contextual
    val subFilterId: UUID? = null,
    @ColumnName("swimlane_strategy")
    val swimlaneStrategy: SwimlaneStrategy = SwimlaneStrategy.NONE,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("modified_at")
    @Contextual
    val modifiedAt: OffsetDateTime = OffsetDateTime.now(),
    val version: Long = 0,
)

/**
 * Join-table row linking a [Board] to one of its constituent projects.
 * Every board has at least one row here — including
 * legacy single-project boards (backfilled in V32).
 */
@Serializable
data class BoardProject(
    @ColumnName("board_id")
    @Contextual
    val boardId: UUID,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("added_at")
    @Contextual
    val addedAt: OffsetDateTime = OffsetDateTime.now(),
)

/** Input for creating a [Board]. Exactly one scope mechanism must be provided. */
@Serializable
data class CreateBoardInput(
    @Contextual
    val projectId: UUID? = null,
    @Contextual
    val programId: UUID? = null,
    @Contextual
    val portfolioId: UUID? = null,
    /** For multi-project boards: the explicit set of projects this board spans. */
    val projectIds: List<@Contextual UUID>? = null,
    val name: String,
    val type: BoardType,
    val swimlaneStrategy: SwimlaneStrategy = SwimlaneStrategy.NONE,
)

/** Input for creating a [BoardColumn]. */
@Serializable
data class CreateBoardColumnInput(
    @Contextual
    val boardId: UUID,
    val name: String,
    val displayOrder: Int,
    val statusIds: List<@Contextual UUID>,
    val wipLimit: Int? = null,
)

/** Input for updating a [Board]'s mutable settings. */
@Serializable
data class UpdateBoardInput(
    val name: String,
    val type: BoardType,
    val swimlaneStrategy: SwimlaneStrategy,
    val expectedVersion: Long,
    val addProjectIds: List<@Contextual UUID>? = null,
    val removeProjectIds: List<@Contextual UUID>? = null,
)

/** Input for updating a [BoardColumn]'s settings. */
@Serializable
data class UpdateBoardColumnInput(
    @Contextual
    val id: UUID,
    val name: String,
    val displayOrder: Int,
    val statusIds: List<@Contextual UUID>,
    val wipLimit: Int? = null,
)
