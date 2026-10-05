@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package bosca.core.platform

actual abstract class PlatformContext

class JvmPlatformContext : PlatformContext()