package bosca.experimentation

/** Shared storage and query-boundary contract for analytics item-extra keys. */
internal fun isValidItemExtraKey(key: String): Boolean = ITEM_EXTRA_KEY.matches(key)

private val ITEM_EXTRA_KEY = Regex("[a-z][a-z0-9_]{0,127}")
