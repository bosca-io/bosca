@file:OptIn(ExperimentalForeignApi::class)

package bosca.core.security.providers

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIViewController

/**
 * App-supplied accessor for the [UIViewController] that native iOS OAuth
 * (Google Sign-In) presents from. The auth library can't know how the host app
 * tracks its current view controller, so the app installs [controllerProvider]
 * during startup; that wiring lives at the app boundary rather than inside the
 * library.
 */
object IosAuthPresenter {
    /** Returns the current presenting view controller, or null if none is available. */
    var controllerProvider: (() -> UIViewController?)? = null
}
