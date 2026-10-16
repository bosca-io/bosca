package bosca.backup.graphql

/** Applies the existing administration page cap without accepting invalid pagination. */
internal fun objectBackupPageSize(offset: Int, limit: Int): Int {
    require(offset >= 0) { "Object backup offset must not be negative" }
    require(limit > 0) { "Object backup limit must be positive" }
    return limit.coerceAtMost(100)
}
