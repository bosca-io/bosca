package bosca.ide.ui

import bosca.ide.repository.BoscaRemoteRepository
import bosca.ide.repository.BoscaRepositoryDiscoveryService
import bosca.ide.review.BoscaDiffFile
import bosca.ide.review.BoscaPullRequestDetail
import bosca.ide.review.BoscaPullRequestService
import bosca.ide.review.BoscaPullRequestSummary
import bosca.ide.review.BoscaReview
import bosca.ide.review.BoscaReviewComment
import bosca.ide.review.BoscaDiffSide
import bosca.ide.review.BoscaNativeDiff
import bosca.ide.review.BoscaReviewAnchors
import bosca.ide.navigation.BoscaNavigationListener
import bosca.ide.navigation.BoscaNavigationTarget
import bosca.ide.server.BoscaServerRegistry
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CompletableFuture
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.ListSelectionModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** Pull-request dashboard and selected-PR review state for mapped repositories. */
internal class BoscaPullRequestsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val discovery = project.getService(BoscaRepositoryDiscoveryService::class.java)
    private val service = project.getService(BoscaPullRequestService::class.java)
    private val repositoryModel = DefaultComboBoxModel<RepositoryChoice>()
    private val repositoryBox = JComboBox(repositoryModel)
    private val statusBox = JComboBox(arrayOf("OPEN", "DRAFT", "CLOSED", "MERGED", "ALL"))
    private val listModel = DefaultListModel<BoscaPullRequestSummary>()
    private val list = JBList(listModel)
    private val status = JBLabel("Select a mapped repository")
    private val refresh = JButton("Refresh")
    private val title = JBTextField()
    private val description = JBTextArea()
    private val overview = JBTextArea()
    private val reviewsRoot = DefaultMutableTreeNode("Reviews")
    private val reviewsModel = DefaultTreeModel(reviewsRoot)
    private val reviews = Tree(reviewsModel)
    private val filesModel = DefaultListModel<BoscaDiffFile>()
    private val files = JBList(filesModel)
    private val save = JButton("Save")
    private val assign = JButton("Assign…")
    private val unassign = JButton("Unassign…")
    private val ready = JButton("Ready for Review")
    private val close = JButton("Close")
    private val reopen = JButton("Reopen")
    private val merge = JButton("Merge…")
    private val openDiff = JButton("Open Native Diff")
    private val submitReview = JButton("Submit Review…")
    private val addLineComment = JButton("Add Line Comment…")
    private val reply = JButton("Reply…")
    private val resolve = JButton("Resolve Thread")
    private val pollTimer = Timer(10_000) { loadPullRequests(silent = true) }
    private var loadingList = false
    private var loadingDetail = false
    private var detail: BoscaPullRequestDetail? = null
    private var pendingNavigation: BoscaNavigationTarget.PullRequest? = null

    init {
        border = JBUI.Borders.empty(6)
        repositoryBox.prototypeDisplayValue = RepositoryChoice("", "", "A reasonably long repository", "")
        repositoryBox.addActionListener { loadPullRequests() }
        statusBox.addActionListener { loadPullRequests() }
        refresh.addActionListener { refreshRepositories() }

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<BoscaPullRequestSummary>() {
            override fun customizeCellRenderer(
                list: JList<out BoscaPullRequestSummary>,
                value: BoscaPullRequestSummary,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                append("#${value.number}")
                append("  ${value.status}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(value.title)
            }
        }
        list.addListSelectionListener { if (!it.valueIsAdjusting) loadSelectedDetail() }

        description.lineWrap = true
        description.wrapStyleWord = true
        overview.isEditable = false
        overview.lineWrap = true
        overview.wrapStyleWord = true
        reviews.isRootVisible = false
        reviews.addTreeSelectionListener { updateActions() }
        files.cellRenderer = object : ColoredListCellRenderer<BoscaDiffFile>() {
            override fun customizeCellRenderer(
                list: JList<out BoscaDiffFile>,
                value: BoscaDiffFile,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                append(value.changeType)
                append("  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(value.path)
            }
        }
        files.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) openNativeDiff()
            }
        })

        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(repositoryBox)
                add(JBLabel("Status:"))
                add(statusBox)
                add(refresh)
            }, BorderLayout.WEST)
            add(status, BorderLayout.CENTER)
        }, BorderLayout.NORTH)

        val detailTabs = JBTabbedPane().apply {
            addTab("Overview", JBScrollPane(overview))
            addTab("Reviews", JBScrollPane(reviews))
            addTab("Changed Files", JBScrollPane(files))
        }
        val editor = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                add(title, BorderLayout.NORTH)
                add(JBScrollPane(description), BorderLayout.CENTER)
                preferredSize = java.awt.Dimension(100, JBUI.scale(170))
            }, BorderLayout.NORTH)
            add(detailTabs, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(save.apply { addActionListener { saveSelected() } })
                add(assign.apply { addActionListener { assignSelected() } })
                add(unassign.apply { addActionListener { unassignSelected() } })
                add(ready.apply { addActionListener { performReady() } })
                add(close.apply { addActionListener { performClose() } })
                add(reopen.apply { addActionListener { performReopen() } })
                add(merge.apply { addActionListener { performMerge() } })
                add(openDiff.apply { addActionListener { openNativeDiff() } })
                add(submitReview.apply { addActionListener { submitReview() } })
                add(addLineComment.apply { addActionListener { addLineComment() } })
                add(reply.apply { addActionListener { replyToComment() } })
                add(resolve.apply { addActionListener { resolveThread() } })
            }, BorderLayout.SOUTH)
        }
        add(OnePixelSplitter(false, 0.34f).apply {
            firstComponent = JBScrollPane(list)
            secondComponent = editor
        }, BorderLayout.CENTER)

        updateActions()
        pollTimer.start()
        project.messageBus.connect(this).subscribe(BoscaNavigationListener.TOPIC, BoscaNavigationListener { target ->
            if (target is BoscaNavigationTarget.PullRequest) navigate(target)
        })
        refreshRepositories()
    }

    private fun refreshRepositories() {
        if (loadingList || project.isDisposed) return
        loadingList = true
        refresh.isEnabled = false
        status.text = "Discovering mapped repositories…"
        val previous = selectedRepository()?.key
        discovery.refresh().whenComplete { snapshot, error ->
            onUiThread {
                loadingList = false
                refresh.isEnabled = true
                if (error != null) {
                    showError("Repository discovery failed", error)
                    return@onUiThread
                }
                val choices = snapshot.roots.mapNotNull { it.repository }
                    .distinctBy { "${it.serverProfileId}:${it.id}" }
                    .map(::RepositoryChoice)
                    .sortedWith(compareBy({ it.serverName }, { it.name }))
                repositoryModel.removeAllElements()
                choices.forEach(repositoryModel::addElement)
                if (choices.isNotEmpty()) {
                    repositoryBox.selectedIndex = choices.indexOfFirst { it.key == previous }.takeIf { it >= 0 } ?: 0
                    loadPullRequests()
                } else {
                    clearAll()
                    status.text = "Map a local Git root to a Bosca repository on the Overview tab"
                }
            }
        }
    }

    private fun loadPullRequests(silent: Boolean = false) {
        val repository = selectedRepository() ?: return
        if (loadingList || project.isDisposed) return
        loadingList = true
        refresh.isEnabled = false
        if (!silent) status.text = "Loading pull requests…"
        val previous = pendingNavigation?.number ?: list.selectedValue?.number
        val filter = (statusBox.selectedItem as? String)?.takeUnless { it == "ALL" }
        service.list(repository.serverId, repository.repositoryId, filter).whenComplete { values, error ->
            onUiThread {
                loadingList = false
                refresh.isEnabled = true
                if (error != null) {
                    showError("Pull-request refresh failed", error)
                    return@onUiThread
                }
                if (pendingNavigation != null && values.none { it.number == previous } && filter != null) {
                    statusBox.selectedItem = "ALL"
                    return@onUiThread
                }
                listModel.removeAllElements()
                values.forEach(listModel::addElement)
                if (values.isNotEmpty()) {
                    list.selectedIndex = values.indexOfFirst { it.number == previous }.takeIf { it >= 0 } ?: 0
                } else {
                    clearDetail()
                }
                status.text = "${values.size} pull request(s) · ${repository.serverName}"
            }
        }
    }

    private fun loadSelectedDetail() {
        val selected = list.selectedValue ?: run { clearDetail(); return }
        if (loadingDetail) return
        loadingDetail = true
        status.text = "Loading #${selected.number}…"
        service.detail(selected.serverProfileId, selected.repositoryId, selected.number).whenComplete { value, error ->
            onUiThread {
                loadingDetail = false
                if (list.selectedValue?.id != selected.id) {
                    loadSelectedDetail()
                    return@onUiThread
                }
                if (error != null) {
                    showError("Pull-request detail failed", error)
                    return@onUiThread
                }
                detail = value
                renderDetail(value)
                status.text = "#${value.summary.number} · ${value.summary.status} · ${value.files.size} changed file(s)"
            }
        }
    }

    private fun renderDetail(value: BoscaPullRequestDetail) {
        title.text = value.summary.title
        description.text = value.description
        description.caretPosition = 0
        overview.text = buildString {
            appendLine("#${value.summary.number} · ${value.summary.status}")
            appendLine("${value.summary.sourceBranch} → ${value.summary.targetBranch}")
            appendLine("Author: ${value.summary.authorId}")
            appendLine("Assignees: ${value.assignees.joinToString { it.name }.ifEmpty { "None" }}")
            appendLine("Head: ${value.headSha ?: "Unavailable"}")
            appendLine("Mergeable: ${if (value.summary.mergeable) "Yes" else "No"}")
            if (value.conflictingFiles.isNotEmpty()) appendLine("Conflicts: ${value.conflictingFiles.joinToString()}")
            if (value.dependencies.isNotEmpty()) appendLine("Depends on: ${value.dependencies.joinToString()}")
            if (value.dependents.isNotEmpty()) appendLine("Required by: ${value.dependents.joinToString()}")
            appendLine()
            appendLine("Checks")
            if (value.checks.isEmpty()) appendLine("No commit checks reported")
            value.checks.forEach { appendLine("• ${it.context}: ${it.state}${it.description?.let { text -> " · $text" }.orEmpty()}") }
            appendLine()
            appendLine("Pipelines")
            if (value.pipelineRuns.isEmpty()) appendLine("No pipeline runs match the PR head")
            value.pipelineRuns.forEach { appendLine("• #${it.number}: ${it.status} (${it.ref})") }
        }.trimEnd()
        overview.caretPosition = 0

        reviewsRoot.removeAllChildren()
        value.reviews.sortedByDescending { it.created }.forEach { review ->
            val parent = DefaultMutableTreeNode(ReviewNode(review))
            review.comments.forEach { parent.add(DefaultMutableTreeNode(CommentNode(it))) }
            reviewsRoot.add(parent)
        }
        reviewsModel.reload()
        filesModel.removeAllElements()
        value.files.forEach(filesModel::addElement)
        applyPendingNavigation(value)
        updateActions()
    }

    private fun saveSelected() {
        val value = detail ?: return
        val newTitle = title.text.trim().takeIf { it.isNotEmpty() } ?: return
        perform("Saving #${value.summary.number}", service.update(value.summary.serverProfileId, value.summary.id, newTitle, description.text))
    }

    private fun assignSelected() {
        val value = detail ?: return
        val profileId = prompt("Profile UUID to assign:") ?: return
        perform("Assigning #${value.summary.number}", service.assign(value.summary.serverProfileId, value.summary.id, profileId))
    }

    private fun unassignSelected() {
        val value = detail ?: return
        val assignee = javax.swing.JOptionPane.showInputDialog(
            this,
            "Assignee:",
            "Unassign #${value.summary.number}",
            javax.swing.JOptionPane.PLAIN_MESSAGE,
            null,
            value.assignees.toTypedArray(),
            value.assignees.firstOrNull(),
        ) as? bosca.ide.review.BoscaPullRequestAssignee ?: return
        perform("Unassigning #${value.summary.number}", service.unassign(value.summary.serverProfileId, value.summary.id, assignee.id))
    }

    private fun performReady() {
        val value = detail ?: return
        perform("Marking #${value.summary.number} ready", service.markReady(value.summary.serverProfileId, value.summary.id))
    }

    private fun performClose() {
        val value = detail ?: return
        perform("Closing #${value.summary.number}", service.close(value.summary.serverProfileId, value.summary.id))
    }

    private fun performReopen() {
        val value = detail ?: return
        perform("Reopening #${value.summary.number}", service.reopen(value.summary.serverProfileId, value.summary.id))
    }

    private fun performMerge() {
        val value = detail ?: return
        val strategy = javax.swing.JOptionPane.showInputDialog(
            this,
            "Merge strategy:",
            "Merge #${value.summary.number}",
            javax.swing.JOptionPane.PLAIN_MESSAGE,
            null,
            value.mergeStrategies.toTypedArray(),
            value.mergeStrategies.firstOrNull(),
        ) as? String ?: return
        val dependencies = value.dependencies.isNotEmpty() && Messages.showYesNoDialog(
            project,
            "Merge unresolved dependencies first?",
            "Merge #${value.summary.number}",
            null,
        ) == Messages.YES
        perform("Merging #${value.summary.number}", service.merge(value.summary.serverProfileId, value.summary.id, strategy, dependencies))
    }

    private fun openNativeDiff() {
        val value = detail ?: return
        val initial = files.selectedIndex.takeIf { it >= 0 } ?: 0
        status.text = "Loading full before/after content for ${value.files.size} file(s)…"
        openDiff.isEnabled = false
        service.allFileContents(value).whenComplete { contents, error ->
            onUiThread {
                openDiff.isEnabled = true
                if (error != null) {
                    showError("Diff loading failed", error)
                    return@onUiThread
                }
                BoscaNativeDiff.open(project, value, contents, initial)
                status.text = "Opened native diff for #${value.summary.number}"
            }
        }
    }

    private fun submitReview() {
        val value = detail ?: return
        val verdict = javax.swing.JOptionPane.showInputDialog(
            this,
            "Verdict:",
            "Review #${value.summary.number}",
            javax.swing.JOptionPane.PLAIN_MESSAGE,
            null,
            arrayOf("APPROVED", "CHANGES_REQUESTED", "COMMENT_ONLY"),
            "COMMENT_ONLY",
        ) as? String ?: return
        val body = Messages.showMultilineInputDialog(project, "Review summary:", "Submit $verdict", "", null, null)
            ?.trim()?.ifEmpty { null } ?: if (verdict == "COMMENT_ONLY") null else return
        performFuture(
            "Submitting review on #${value.summary.number}",
            service.submitReview(value.summary.serverProfileId, value.summary.id, verdict, body),
        )
    }

    private fun addLineComment() {
        val value = detail ?: return
        val file = files.selectedValue ?: run {
            status.text = "Select a changed file first"
            return
        }
        val side = javax.swing.JOptionPane.showInputDialog(
            this,
            "Diff side:",
            "Comment on ${file.path}",
            javax.swing.JOptionPane.PLAIN_MESSAGE,
            null,
            BoscaDiffSide.entries.toTypedArray(),
            BoscaDiffSide.NEW,
        ) as? BoscaDiffSide ?: return
        val line = prompt("One-based ${side.name.lowercase()} line number:")?.toIntOrNull() ?: return
        val anchor = BoscaReviewAnchors.find(file, side, line) ?: run {
            status.text = "Line $line is not a current ${side.name.lowercase()}-side diff anchor; refresh after a force-push"
            return
        }
        val content = Messages.showMultilineInputDialog(project, "Comment:", "${file.path}:$line", "", null, null)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val headSha = value.headSha ?: run {
            status.text = "The PR head SHA is unavailable; refresh before commenting"
            return
        }
        val operation = service.submitReview(value.summary.serverProfileId, value.summary.id, "COMMENT_ONLY", null)
            .thenCompose { review ->
                service.addReviewComment(
                    value.summary.serverProfileId,
                    value.summary.id,
                    review.id,
                    file.path,
                    anchor.oldLine,
                    anchor.newLine,
                    headSha,
                    content,
                )
            }
        perform("Adding line comment", operation)
    }

    private fun replyToComment() {
        val value = detail ?: return
        val comment = selectedComment() ?: return
        if (comment.outdated) {
            status.text = "Outdated anchors are read-only; add a new comment on a current diff line"
            return
        }
        val content = Messages.showMultilineInputDialog(project, "Reply:", "Reply on ${comment.filePath}", "", null, null)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        perform(
            "Replying to review thread",
            service.addReviewComment(
                value.summary.serverProfileId,
                value.summary.id,
                comment.reviewId,
                comment.filePath,
                comment.oldLineNumber,
                comment.newLineNumber,
                comment.commitSha,
                content,
            ),
        )
    }

    private fun resolveThread() {
        val value = detail ?: return
        val comment = selectedComment() ?: return
        val line = comment.newLineNumber ?: comment.oldLineNumber ?: return
        perform(
            "Resolving review thread",
            service.resolveThread(value.summary.serverProfileId, value.summary.id, comment.filePath, line),
        )
    }

    private fun perform(label: String, operation: CompletableFuture<Boolean>) {
        setActionsEnabled(false)
        status.text = "$label…"
        operation.whenComplete { _, error ->
            onUiThread {
                if (error != null) {
                    showError("$label failed", error)
                    updateActions()
                } else {
                    status.text = "$label succeeded"
                    loadPullRequests(silent = true)
                    loadSelectedDetail()
                }
            }
        }
    }

    private fun <T> performFuture(label: String, operation: CompletableFuture<T>) {
        setActionsEnabled(false)
        status.text = "$label…"
        operation.whenComplete { _, error ->
            onUiThread {
                if (error != null) {
                    showError("$label failed", error)
                    updateActions()
                } else {
                    status.text = "$label succeeded"
                    loadSelectedDetail()
                }
            }
        }
    }

    private fun updateActions() {
        val value = detail
        save.isEnabled = value != null
        assign.isEnabled = value != null && value.summary.status in setOf("OPEN", "DRAFT")
        unassign.isEnabled = value?.assignees?.isNotEmpty() == true
        ready.isEnabled = value?.summary?.status == "DRAFT"
        close.isEnabled = value?.summary?.status in setOf("OPEN", "DRAFT")
        reopen.isEnabled = value?.summary?.status == "CLOSED"
        merge.isEnabled = value?.summary?.status == "OPEN" && value.summary.mergeable && value.mergeStrategies.isNotEmpty()
        openDiff.isEnabled = value?.files?.isNotEmpty() == true
        submitReview.isEnabled = value?.summary?.status == "OPEN"
        addLineComment.isEnabled = value?.summary?.status == "OPEN" && value.files.isNotEmpty()
        val comment = selectedComment()
        reply.isEnabled = comment != null && !comment.outdated
        resolve.isEnabled = comment != null && !comment.resolved && !comment.outdated
    }

    private fun setActionsEnabled(enabled: Boolean) {
        save.isEnabled = enabled
        assign.isEnabled = enabled
        unassign.isEnabled = enabled
        ready.isEnabled = enabled
        close.isEnabled = enabled
        reopen.isEnabled = enabled
        merge.isEnabled = enabled
        openDiff.isEnabled = enabled
        submitReview.isEnabled = enabled
        addLineComment.isEnabled = enabled
        reply.isEnabled = enabled
        resolve.isEnabled = enabled
    }

    private fun clearDetail() {
        detail = null
        title.text = ""
        description.text = ""
        overview.text = ""
        reviewsRoot.removeAllChildren()
        reviewsModel.reload()
        filesModel.removeAllElements()
        updateActions()
    }

    private fun clearAll() {
        listModel.removeAllElements()
        clearDetail()
    }

    private fun selectedRepository() = repositoryBox.selectedItem as? RepositoryChoice

    private fun navigate(target: BoscaNavigationTarget.PullRequest) {
        pendingNavigation = target
        val index = (0 until repositoryModel.size).firstOrNull { position ->
            repositoryModel.getElementAt(position).let {
                it.serverId == target.serverProfileId && it.repositoryId == target.repositoryId
            }
        }
        if (index != null) {
            repositoryBox.selectedIndex = index
            if (!loadingList) loadPullRequests()
        } else {
            refreshRepositories()
        }
    }

    private fun applyPendingNavigation(value: BoscaPullRequestDetail) {
        val target = pendingNavigation?.takeIf { it.number == value.summary.number } ?: return
        target.filePath?.let { path ->
            val fileIndex = value.files.indexOfFirst { it.path == path }
            if (fileIndex >= 0) files.selectedIndex = fileIndex
            val comments = reviewsRoot.depthFirstEnumeration()
            while (comments.hasMoreElements()) {
                val node = comments.nextElement() as? DefaultMutableTreeNode ?: continue
                val comment = (node.userObject as? CommentNode)?.value ?: continue
                val line = comment.newLineNumber ?: comment.oldLineNumber
                if (comment.filePath == path && (target.lineNumber == null || target.lineNumber == line)) {
                    reviews.selectionPath = TreePath(node.path)
                    break
                }
            }
        }
        pendingNavigation = null
    }

    private fun selectedComment(): BoscaReviewComment? =
        ((reviews.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? CommentNode)?.value

    private fun prompt(message: String): String? =
        Messages.showInputDialog(project, message, "Bosca Pull Request", null)?.trim()?.takeIf { it.isNotEmpty() }

    private fun showError(prefix: String, error: Throwable) {
        status.text = "$prefix: ${rootMessage(error)}"
    }

    private fun rootMessage(error: Throwable): String {
        var current = error
        while (current.cause != null) current = current.cause ?: break
        return current.message ?: current.javaClass.simpleName
    }

    private fun onUiThread(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) block() }
    }

    override fun dispose() {
        pollTimer.stop()
    }

    private data class RepositoryChoice(
        val serverId: String,
        val repositoryId: String,
        val name: String,
        val serverName: String,
    ) {
        constructor(value: BoscaRemoteRepository) : this(
            value.serverProfileId,
            value.id,
            value.name,
            BoscaServerRegistry.getInstance().profile(value.serverProfileId)?.name ?: value.serverProfileId,
        )
        val key get() = "$serverId:$repositoryId"
        override fun toString() = "$serverName · $name"
    }

    private data class ReviewNode(val value: BoscaReview) {
        override fun toString() = "${value.status} · ${value.reviewerId} · ${value.created}${value.body?.let { " · $it" }.orEmpty()}"
    }

    private data class CommentNode(val value: BoscaReviewComment) {
        override fun toString() = "${when { value.outdated -> "Outdated"; value.resolved -> "Resolved"; else -> "Open" }} · ${value.filePath}:${value.newLineNumber ?: value.oldLineNumber} · ${value.content}"
    }
}
