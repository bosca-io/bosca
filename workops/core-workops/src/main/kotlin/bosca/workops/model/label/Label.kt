package bosca.workops.model.label

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@DbMapper(LabelScopeMapper::class)
@Serializable
enum class LabelScope {
    GLOBAL,
    PORTFOLIO,
    PROGRAM,
    PROJECT,
}

object LabelScopeMapper : EnumMapper<LabelScope>({ LabelScope.valueOf(it.uppercase()) })

@BatchKey("id")
@Serializable
data class Label(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    @ColumnName("color_hex")
    val colorHex: String? = null,
    val scope: LabelScope = LabelScope.GLOBAL,
    @ColumnName("portfolio_id")
    @Contextual
    val portfolioId: UUID? = null,
    @ColumnName("program_id")
    @Contextual
    val programId: UUID? = null,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID? = null,
    val version: Long = 0,
)

@Serializable
data class CreateLabelInput(
    val name: String,
    val colorHex: String? = null,
    val scope: LabelScope = LabelScope.GLOBAL,
    @Contextual
    val portfolioId: UUID? = null,
    @Contextual
    val programId: UUID? = null,
    @Contextual
    val projectId: UUID? = null,
)
