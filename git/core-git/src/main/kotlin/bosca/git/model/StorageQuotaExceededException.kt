package bosca.git.model

/**
 * Thrown when an owner's total repository disk usage exceeds the configured
 * storage quota, preventing creation of new repositories or forks.
 */
class StorageQuotaExceededException(message: String) : RuntimeException(message)
