package bosca.pages

import bosca.server.BoscaApplication
import gg.jte.support.LocalizationSupport

object PageTemplate {

    fun BoscaApplication.execute(localization: LocalizationSupport, details: PageDetails, block: BoscaApplication.() -> String): String {
        return PageContext.run {
            PageContext.setLocalization(localization)
            PageContext.setPageDetails(details)
            block()
        }
    }
}
