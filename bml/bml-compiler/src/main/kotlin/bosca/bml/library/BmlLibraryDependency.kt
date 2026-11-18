package bosca.bml.library

import java.io.File

/** A validated binary dependency and the classpath entry that supplied its manifest. */
data class BmlLibraryDependency(val artifact: File, val manifest: BmlLibraryManifest)
