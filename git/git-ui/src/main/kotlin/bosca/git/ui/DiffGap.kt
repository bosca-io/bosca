package bosca.git.ui

import kotlinx.serialization.Serializable

/**
 * Hidden unchanged lines [newStart]..[newEnd] (new-file numbering) above hunk [index]; an index
 * equal to the hunk count is the end of the file. [oldOffset] converts to old-file numbering.
 */
@Serializable
data class DiffGap(
    val index: Int,
    val newStart: Int,
    val newEnd: Int,
    val oldOffset: Int,
    val canExpandUp: Boolean,
    val canExpandDown: Boolean,
    val heading: String,
) {
    val size: Int
        get() = newEnd - newStart + 1

    /** Small gaps open in one step instead of offering both directions. */
    val expandsAtOnce: Boolean
        get() = size <= CONTEXT_EXPANSION_STEP

    val label: String
        get() = if (size == 1) "1 hidden line" else "$size hidden lines"
}
