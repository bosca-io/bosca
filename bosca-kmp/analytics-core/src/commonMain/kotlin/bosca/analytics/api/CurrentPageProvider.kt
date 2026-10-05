package bosca.analytics.api

/** Supplies an optional screen/page snapshot at event creation time. */
fun interface CurrentPageProvider {
    /** Returns the current route or screen snapshot when one is available. */
    fun currentPage(): Page?
}
