package bosca.git.ui

import kotlinx.serialization.Serializable

/** A hunk with the hidden lines above it, if they can be revealed. */
@Serializable
data class DiffSection(val index: Int, val gap: DiffGap?, val hunk: DiffHunkView)
