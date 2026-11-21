package bosca.design.catalog

import bosca.design.model.BoardMoveTarget
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Catalog-owned content and save decisions demonstrate the shared movement request contract. */
@Serializable
class CatalogBoard(
    val id: String,
    var cards: List<CatalogBoardCard> = listOf(CatalogBoardCard("proposal", "Review the proposal", "todo"), CatalogBoardCard("fixed", "Read-only reference", "doing", false)) +
        (1..18).map { CatalogBoardCard("item-$it", "Example item $it", "todo") },
    var readOnly: Boolean = false,
    var disabled: Boolean = false,
    var reject: Boolean = false,
    var visible: Boolean = true,
    var refreshCount: Int = 0,
) {
    val columns get() = listOf(BoardMoveTarget("todo", "To do"), BoardMoveTarget("doing", "In progress"), BoardMoveTarget("review", "Review"), BoardMoveTarget("done", "Done"))
    fun cardsFor(column: String) = cards.filter { it.column == column }
    fun countLabel(column: String) = cardsFor(column).size.let { "$it ${if (it == 1) "item" else "items"}" }
    suspend fun move(cardId: String, columnId: String) {
        delay(250)
        require(!readOnly && !disabled && !reject) { "This example rejected the move. Try another destination or turn off rejection." }
        require(cards.any { it.id == cardId && it.movable } && columns.any { it.id == columnId }) { "This item cannot move to that destination." }
        cards = cards.map { if (it.id == cardId) it.copy(column = columnId) else it }
    }
    fun refresh() { refreshCount++ }
    fun toggleReadOnly() { readOnly = !readOnly }
    fun toggleDisabled() { disabled = !disabled }
    fun toggleRejection() { reject = !reject }
    fun toggleVisible() { visible = !visible }
}
