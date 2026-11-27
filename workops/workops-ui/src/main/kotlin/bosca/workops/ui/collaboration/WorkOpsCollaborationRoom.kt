package bosca.workops.ui.collaboration

import bosca.serialization.UUID

/** Content identity shared by all wire spellings of the same document room. */
internal data class WorkOpsCollaborationRoom(val metadataId: UUID, val version: Int) {
    val contentPath: String get() = "/api/v1/content/metadata/$metadataId/document/collaboration?version=$version"

    companion object {
        private val namePattern = Regex("^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.([0-9]+)$")

        fun parse(name: String): WorkOpsCollaborationRoom {
            val match = namePattern.matchEntire(name) ?: error("Invalid collaboration document name")
            val version = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
                ?: error("Invalid collaboration document version")
            return WorkOpsCollaborationRoom(UUID.parse(match.groupValues[1]), version)
        }
    }
}
