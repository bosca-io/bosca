package bosca.cache.postgres

import bosca.cache.CacheValue

internal data class PostgresCacheValue(override val value: String?, override val exists: Boolean) : CacheValue
