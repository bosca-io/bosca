package bosca.bml.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** Settings for a compiled BML component library and its source attachment. */
abstract class BmlLibraryExtension {
    /** Whether this module produces a library manifest. Set by `library { ... }`. */
    abstract val enabled: Property<Boolean>

    /** Component tags visible to consumers. Other declarations remain implementation details. */
    abstract val exports: ListProperty<String>
}
