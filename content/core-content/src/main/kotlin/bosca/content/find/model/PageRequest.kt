package bosca.content.find.model

import java.util.*

/**
 * Represents a request for a specific page of results.
 *
 * @param pageSize Number of items to return per page
 * @param pageNumber Zero-based page number to retrieve
 * @param pageToken Optional token for cursor-based pagination
 */
data class PageRequest(
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val pageNumber: Int = 0,
    val pageToken: String? = null
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 1000
        const val MIN_PAGE_SIZE = 1
    }

    /**
     * Calculates the offset for SQL LIMIT/OFFSET queries
     */
    val offset: Long = pageNumber.toLong() * pageSize.toLong()

    /**
     * Creates a PageRequest for the next page
     */
    fun nextPage(): PageRequest = copy(pageNumber = pageNumber + 1)

    /**
     * Creates a PageRequest for the previous page
     */
    fun previousPage(): PageRequest? = if (pageNumber > 0) {
        copy(pageNumber = pageNumber - 1)
    } else null

    init {
        require(pageSize in MIN_PAGE_SIZE..MAX_PAGE_SIZE) {
            "Page size must be between $MIN_PAGE_SIZE and $MAX_PAGE_SIZE, but was $pageSize"
        }
        require(pageNumber >= 0) { "Page number must be non-negative, but was $pageNumber" }
    }
}

/**
 * Extension function to generate a page token from page number
 */
fun PageRequest.generateToken(): String {
    return Base64.getEncoder().encodeToString("$pageNumber:$pageSize".toByteArray())
}

/**
 * Extension function to parse a page token back to page number and size
 */
fun parsePageToken(token: String): Pair<Int, Int>? {
    return try {
        val decoded = String(Base64.getDecoder().decode(token))
        val parts = decoded.split(":")
        if (parts.size == 2) {
            Pair(parts[0].toInt(), parts[1].toInt())
        } else null
    } catch (e: Exception) {
        null
    }
}