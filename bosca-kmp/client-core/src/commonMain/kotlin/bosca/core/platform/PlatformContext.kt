@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package bosca.core.platform

expect abstract class PlatformContext

object PlatformContexts {

    private var _context: PlatformContext? = null

    fun onCreate(context: PlatformContext) {
        this._context = context
    }

    fun onDestroy() {
        _context = null
    }

    val context: PlatformContext get() = _context ?: throw IllegalStateException("Context not set")
}