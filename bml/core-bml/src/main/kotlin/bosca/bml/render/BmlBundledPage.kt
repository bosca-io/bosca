package bosca.bml.render

import bosca.bml.library.BmlApplicationAssets

/** Optional asset capability implemented by compiler-generated application pages. */
interface BmlBundledPage {
    /** The application's direct, generation-specific packaged resource reader. */
    val assets: BmlApplicationAssets
}
