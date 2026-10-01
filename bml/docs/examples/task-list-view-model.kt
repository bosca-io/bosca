package example

import kotlinx.serialization.Serializable

@Serializable
data class TaskItem(
    val id: String,
    val title: String,
    val completed: Boolean = false,
)

/** Mutable, serializable state used by task-list.bml's declarative server actions. */
@Serializable
class TaskListViewModel {
    var items: List<TaskItem> = listOf(
        TaskItem(id = "1", title = "Read the BML guide", completed = true),
        TaskItem(id = "2", title = "Build an island"),
    )
        private set

    private var nextId: Int = 3

    fun add(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        items = items + TaskItem(id = (nextId++).toString(), title = trimmed)
    }

    fun toggle(id: String) {
        items = items.map { item ->
            if (item.id == id) item.copy(completed = !item.completed) else item
        }
    }

    fun remove(id: String) {
        items = items.filterNot { it.id == id }
    }
}
