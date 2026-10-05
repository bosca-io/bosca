package bosca.content.find.model

/**
 * Represents a paginated result set with metadata about pagination state.
 *
 * @param T The type of items in the result set
 * @param items The items in the current page
 * @param totalCount Total number of items across all pages
 * @param pageSize Number of items per page
 * @param currentPage Zero-based current page number
 * @param hasNextPage Whether there are more pages available
 * @param nextPageToken Optional token for cursor-based pagination
 */
data class PaginatedResult<T>(
    val items: List<T>,
    val totalCount: Long,
    val pageSize: Int,
    val currentPage: Int,
    val hasNextPage: Boolean,
    val nextPageToken: String? = null
) {
    /**
     * Total number of pages based on totalCount and pageSize
     */
    val totalPages: Int = if (pageSize > 0) {
        ((totalCount + pageSize - 1) / pageSize).toInt()
    } else 0

    /**
     * Whether there is a previous page available
     */
    val hasPreviousPage: Boolean = currentPage > 0

    /**
     * Zero-based page number of the next page, or null if no next page
     */
    val nextPage: Int? = if (hasNextPage) currentPage + 1 else null

    /**
     * Zero-based page number of the previous page, or null if no previous page
     */
    val previousPage: Int? = if (hasPreviousPage) currentPage - 1 else null

    init {
        require(totalCount >= 0) { "Total count must be non-negative" }
        require(pageSize > 0) { "Page size must be positive" }
        require(currentPage >= 0) { "Current page must be non-negative" }
        require(items.size <= pageSize) { "Items size cannot exceed page size" }
    }
}