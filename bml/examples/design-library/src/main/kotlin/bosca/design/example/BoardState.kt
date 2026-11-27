package bosca.design.example

import bosca.design.model.BoardMoveTarget
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** This independent application owns its content and move outcome, with no catalog or WorkOps dependency. */
@Serializable
class BoardState(
    val id: String,
    var column: String = "todo",
    var readOnly: Boolean = false,
    var disabled: Boolean = false,
    var reject: Boolean = false,
    var visible: Boolean = true,
    var refreshCount: Int = 0,
) {
    val columns get() = listOf(BoardMoveTarget("todo", "To do"), BoardMoveTarget("doing", "In progress"), BoardMoveTarget("review", "Review"), BoardMoveTarget("done", "Done"))
    suspend fun move(cardId: String, columnId: String) {
        delay(250)
        require(!readOnly && !disabled && !reject) { "This example rejected the move. Try another destination or turn off rejection." }
        require(cardId == "proposal" && columns.any { it.id == columnId }) { "This item cannot move to that destination." }
        column = columnId
    }
    fun refresh() { refreshCount++ }
    fun toggleReadOnly() { readOnly = !readOnly }
    fun toggleDisabled() { disabled = !disabled }
    fun toggleRejection() { reject = !reject }
    fun toggleVisible() { visible = !visible }
}
