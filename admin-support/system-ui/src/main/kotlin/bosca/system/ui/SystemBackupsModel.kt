package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable

@Serializable
class SystemBackupsModel(
    val admin: Boolean = false,
    val offset: Int = 0,
    var entries: List<SystemBackupsData.Backups.Records> = emptyList(),
    var hasNext: Boolean = false,
    var mode: String = "",
    var targetId: String = "",
    var includeFiles: Boolean = false,
    var strategy: String = "SKIP",
    var confirmed: Boolean = false,
    var dialogRequest: Long = 0,
    val feedback: SystemFeedback = SystemFeedback(),
) {
    val previousHref: String get() = if (offset > 0) "/system/backups?offset=${(offset - 25).coerceAtLeast(0)}" else ""
    val nextHref: String get() = if (hasNext) "/system/backups?offset=${offset.toLong() + 25}" else ""

    suspend fun reload(ctx: RenderContext) {
        if (!admin) return
        val result = ctx.gql.execute(SystemBackups, SystemBackups.Variables(offset)).backups.records
        hasNext = result.size > 25
        entries = result.take(25)
    }

    suspend fun refresh(ctx: RenderContext) = feedback.perform { reload(ctx) }

    fun prepare(action: String, id: String) {
        require(action in setOf("create", "restore", "delete")) { "Unknown backup action." }
        mode = action
        targetId = id
        includeFiles = false
        strategy = "SKIP"
        confirmed = false
        dialogRequest++
        feedback.message = ""
        feedback.failed = false
    }

    fun cancel() {
        mode = ""; targetId = ""; confirmed = false
        feedback.failed = false; feedback.message = ""
    }

    suspend fun create(files: Boolean, ctx: RenderContext) = feedback.perform {
        includeFiles = files
        mode = "create"
        require(admin) { "Administrator access is required." }
        ctx.gql.execute(SystemCreateBackup, SystemCreateBackup.Variables(files))
        mode = ""
        reload(ctx)
        feedback.message = "Backup started. Refresh to see its progress."
    }

    suspend fun restore(id: String, conflictStrategy: String, confirmation: Boolean, ctx: RenderContext) = feedback.perform {
        targetId = id
        strategy = conflictStrategy
        confirmed = confirmation
        mode = "restore"
        require(admin && confirmation) { "Confirm backup restoration." }
        val selectedStrategy = ConflictStrategy.valueOf(conflictStrategy)
        val backup = ctx.gql.execute(SystemBackup, SystemBackup.Variables(id)).backups.backup
        require(backup?.status.equals("completed", ignoreCase = true) && !backup?.path.isNullOrBlank()) { "Only completed backups can be restored." }
        check(ctx.gql.execute(SystemRestoreBackup, SystemRestoreBackup.Variables(id, selectedStrategy)).backups.restore) { "Restore was not started." }
        mode = ""
        reload(ctx)
        feedback.message = "Restore started."
    }

    suspend fun delete(id: String, confirmation: Boolean, ctx: RenderContext) = feedback.perform {
        targetId = id
        confirmed = confirmation
        mode = "delete"
        require(admin && confirmation) { "Confirm backup deletion." }
        check(ctx.gql.execute(SystemDeleteBackup, SystemDeleteBackup.Variables(id)).backups.delete) { "Backup was not deleted." }
        mode = ""
        reload(ctx)
        feedback.message = "Backup deleted."
    }
}

suspend fun systemBackups(): SystemBackupsModel {
    val ctx = currentRenderContext()
    return SystemBackupsModel(
        admin = systemAdministrator(ctx),
        offset = ctx.query["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    ).also { model -> model.feedback.perform { model.reload(ctx) } }
}
