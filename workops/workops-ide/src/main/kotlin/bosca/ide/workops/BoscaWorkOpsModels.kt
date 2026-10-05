package bosca.ide.workops

data class BoscaWorkOpsProject(
    val serverProfileId: String,
    val id: String,
    val key: String,
    val name: String,
)

data class BoscaWorkOpsTransition(
    val id: String,
    val name: String,
    val toStatus: String,
    val toCategory: String,
) {
    override fun toString(): String = "$name → $toStatus"
}

data class BoscaWorkOpsResolution(val id: String, val name: String) {
    override fun toString(): String = name
}

data class BoscaWorkOpsLinkType(val id: String, val name: String, val outwardLabel: String) {
    override fun toString(): String = outwardLabel
}

data class BoscaWorkOpsComment(
    val id: Long,
    val author: String,
    val created: String,
    val content: String,
)

data class BoscaWorkOpsTask(
    val serverProfileId: String,
    val projectId: String,
    val id: String,
    val key: String,
    val summary: String,
    val descriptionMarkdown: String,
    val status: String,
    val statusCategory: String,
    val priority: String,
    val taskType: String,
    val assigneeProfileId: String?,
    val assigneeName: String?,
    val modifiedAt: String,
    val version: Long,
    val transitions: List<BoscaWorkOpsTransition>,
    val comments: List<BoscaWorkOpsComment>,
    val links: List<String>,
    val history: List<String>,
)

data class BoscaWorkOpsSpec(
    val serverProfileId: String,
    val projectId: String,
    val id: String,
    val key: String,
    val name: String,
    val metadataId: String,
    val metadataVersion: Int,
    val markdown: String,
    val status: String,
    val statusCategory: String,
    val ownerName: String?,
    val modifiedAt: String,
    val version: Long,
    val transitions: List<BoscaWorkOpsTransition>,
    val requirements: List<BoscaWorkOpsRequirement>,
    val comments: List<BoscaWorkOpsComment>,
    val history: List<String>,
)

data class BoscaWorkOpsRequirement(
    val serverProfileId: String,
    val id: String,
    val key: String,
    val metadataId: String,
    val status: String,
    val statusCategory: String,
    val priority: String,
    val assigneeName: String?,
    val taskId: String?,
    val taskVersion: Long?,
    val transitions: List<BoscaWorkOpsTransition>,
    val version: Long,
)

data class BoscaWorkOpsDocument(
    val metadataId: String,
    val version: Int,
    val title: String,
    val markdown: String,
)

data class BoscaWorkOpsBundle(
    val tasks: List<BoscaWorkOpsTask>,
    val specs: List<BoscaWorkOpsSpec>,
    val resolutions: List<BoscaWorkOpsResolution>,
    val linkTypes: List<BoscaWorkOpsLinkType>,
)
