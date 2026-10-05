package bosca.pages

import gg.jte.support.LocalizationSupport

object PageContext {
    private val pageDetails = object : ThreadLocal<PageDetails>() {
        override fun initialValue() = null
    }
    private val localization = ThreadLocal<LocalizationSupport>()
    private val inContext = object : ThreadLocal<Boolean>() {
        override fun initialValue() = false
    }

    fun setPageDetails(details: PageDetails) {
        if (!inContext.get()) throw IllegalStateException("Not running within context")
        pageDetails.set(details)
    }

    fun setLocalization(localizer: LocalizationSupport) {
        if (!inContext.get()) throw IllegalStateException("Not running within context")
        localization.set(localizer)
    }

    val details: PageDetails
        get() = pageDetails.get() ?: throw IllegalStateException("No page details set")

    fun localize(key: String) = localization.get().localize(key)
    fun localize(key: String, vararg params: Any?) = localization.get().localize(key, *params)

    fun <T> run(block: () -> T): T {
        try {
            inContext.set(true)
            return block()
        } finally {
            clear()
        }
    }

    fun clear() {
        pageDetails.remove()
        localization.remove()
        inContext.set(false)
    }
}
