package bosca.graphql

/**
 * Post-processing filter applied to a [Batch]'s resolved items before returning results.
 *
 * Allows permission checks or other filtering to be applied after batch resolution,
 * potentially nulling out items the current user should not see.
 *
 * @param K the key type
 * @param BV the batch value type
 */
interface BatchFilter<K, BV> {

    /**
     * Filters the resolved batch items, returning a list in the same order.
     * Items that fail the filter should be returned as `null`.
     */
    suspend fun filter(items: List<BatchItem<K, BV>>): List<BV?>
}

open class Batch<K, BV>(keys: List<K>) {

    private val items: List<BatchItem<K, BV>> = keys.map { BatchItem(it) }

    private val itemsByKey = this.items.associateBy { it.key }

    var filter: BatchFilter<K, BV>? = null

    open suspend fun setData(keys: List<K>, items: List<BV?>) {
        keys.forEachIndexed { index, key ->
            items[index]?.let { setData(key, it) }
        }
    }

    open suspend fun setData(keys: List<K>, items: Map<K, BV?>) {
        keys.forEach { key ->
            items[key]?.let { setData(key, it) }
        }
    }

    fun getData(key: K): BV? {
        return itemsByKey[key]?.data
    }

    open suspend fun setData(index: Int, data: BV) {
        val item = items[index]
        item.data = data
    }

    open suspend fun setData(key: K, data: BV) {
        itemsByKey[key]?.data = data
    }

    open fun ensureNotNull(default: BV) {
        items.asSequence()
            .forEach {
                if (it.data == null) {
                    it.data = default
                }
            }
    }

    val keys: List<K>
        get() = items.map { it.key }

    suspend fun getResults(): List<BV?> {
        val filter = this.filter ?: return items.map { it.data }
        return filter.filter(items)
    }
}

class BatchItem<K, BV>(val key: K) {

    var data: BV? = null
}

class BatchMapper<K, BV, BM>(private val original: Batch<K, BM>, private val mapper: suspend (BV?) -> BM?) : Batch<K, BV>(original.keys) {

    override suspend fun setData(keys: List<K>, items: List<BV?>) {
        original.setData(keys, items.map { mapper(it) })
        super.setData(keys, items)
    }

    override suspend fun setData(keys: List<K>, items: Map<K, BV?>) {
        val m = items.map { it.key to mapper(it.value) }.associate { it }
        original.setData(keys, m)
        super.setData(keys, items)
    }

    override suspend fun setData(index: Int, data: BV) {
        mapper(data)?.let { original.setData(index, it) }
        super.setData(index, data)
    }

    override suspend fun setData(key: K, data: BV) {
        mapper(data)?.let { original.setData(key, it) }
        super.setData(key, data)
    }

    fun originalEnsureNotNull(default: BM) {
        original.ensureNotNull(default)
    }
}
