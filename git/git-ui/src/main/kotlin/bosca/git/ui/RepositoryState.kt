package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
enum class RepositoryState(val label: String) {
    ACTIVE("Active"),
    ARCHIVED("Archived"),
    ALL("All");

    val queryValue: String
        get() = name.lowercase()

    companion object {
        fun fromQuery(value: String?): RepositoryState =
            entries.firstOrNull { it.queryValue == value } ?: ACTIVE
    }
}
