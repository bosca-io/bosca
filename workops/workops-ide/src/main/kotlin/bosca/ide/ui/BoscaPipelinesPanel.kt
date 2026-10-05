package bosca.ide.ui

import bosca.ide.pipeline.BoscaPipeline
import bosca.ide.pipeline.BoscaPipelineJob
import bosca.ide.pipeline.BoscaPipelineLogLine
import bosca.ide.pipeline.BoscaPipelineRun
import bosca.ide.pipeline.BoscaPipelineService
import bosca.ide.pipeline.BoscaPipelineStep
import bosca.ide.pipeline.isPipelineActive
import bosca.ide.navigation.BoscaNavigationListener
import bosca.ide.navigation.BoscaNavigationTarget
import bosca.ide.repository.BoscaRemoteRepository
import bosca.ide.repository.BoscaRepositoryDiscoveryService
import bosca.ide.server.BoscaServerRegistry
import bosca.ide.server.BoscaSubscription
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.util.concurrent.CompletableFuture
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.TreeSelectionEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/** Pipelines, runs, jobs, steps, controls, and live logs for mapped Bosca repositories. */
internal class BoscaPipelinesPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val discovery = project.getService(BoscaRepositoryDiscoveryService::class.java)
    private val pipelines = project.getService(BoscaPipelineService::class.java)
    private val repositoryModel = DefaultComboBoxModel<RepositoryChoice>()
    private val repositoryBox = JComboBox(repositoryModel)
    private val root = DefaultMutableTreeNode("Pipelines")
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)
    private val details = JBTextArea()
    private val logs = JBTextArea()
    private val status = JBLabel("Select a mapped Bosca repository")
    private val refreshButton = JButton("Refresh")
    private val triggerButton = JButton("Run…")
    private val cancelButton = JButton("Cancel")
    private val rerunButton = JButton("Rerun")
    private val rerunFailedButton = JButton("Rerun Failed")
    private val approveButton = JButton("Approve…")
    private val rejectButton = JButton("Reject…")
    private val pollTimer = Timer(5_000) { refreshPipelines(silent = true) }
    private var refreshing = false
    private var logSubscription: BoscaSubscription? = null
    private var pendingRunId: String? = null

    init {
        border = JBUI.Borders.empty(6)
        repositoryBox.prototypeDisplayValue = RepositoryChoice("", "", "A reasonably long repository name", "main")
        repositoryBox.addActionListener { refreshPipelines() }
        tree.isRootVisible = false
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.addTreeSelectionListener(::selectionChanged)

        details.isEditable = false
        details.lineWrap = true
        details.wrapStyleWord = true
        details.border = JBUI.Borders.empty(6)
        logs.isEditable = false
        logs.font = Font(Font.MONOSPACED, Font.PLAIN, logs.font.size)
        logs.border = JBUI.Borders.empty(6)

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(repositoryBox)
            add(refreshButton.apply { addActionListener { refreshRepositories() } })
            add(triggerButton.apply { addActionListener { triggerSelectedPipeline() } })
            add(cancelButton.apply { addActionListener { cancelSelection() } })
            add(rerunButton.apply { addActionListener { rerunSelection() } })
            add(rerunFailedButton.apply { addActionListener { rerunFailedSelection() } })
            add(approveButton.apply { addActionListener { decideApproval(approve = true) } })
            add(rejectButton.apply { addActionListener { decideApproval(approve = false) } })
        }
        add(JPanel(BorderLayout()).apply {
            add(controls, BorderLayout.WEST)
            add(status, BorderLayout.CENTER)
        }, BorderLayout.NORTH)

        val detailsAndLogs = OnePixelSplitter(true, 0.28f).apply {
            firstComponent = titledPanel("Details", JBScrollPane(details))
            secondComponent = titledPanel("Step log", JBScrollPane(logs))
        }
        add(OnePixelSplitter(false, 0.36f).apply {
            firstComponent = titledPanel("Runs", JBScrollPane(tree))
            secondComponent = detailsAndLogs
        }, BorderLayout.CENTER)

        updateActions()
        pollTimer.isRepeats = true
        pollTimer.start()
        project.messageBus.connect(this).subscribe(BoscaNavigationListener.TOPIC, BoscaNavigationListener { target ->
            if (target is BoscaNavigationTarget.Pipeline) navigate(target)
        })
        refreshRepositories()
    }

    private fun refreshRepositories() {
        if (project.isDisposed || refreshing) return
        refreshing = true
        refreshButton.isEnabled = false
        status.text = "Discovering mapped repositories…"
        val previous = selectedRepository()?.key
        discovery.refresh().whenComplete { snapshot, error ->
            onUiThread {
                refreshing = false
                refreshButton.isEnabled = true
                if (error != null) {
                    showError("Repository discovery failed", error)
                    return@onUiThread
                }
                val repositories = snapshot.roots.mapNotNull { it.repository }
                    .distinctBy { "${it.serverProfileId}:${it.id}" }
                    .map(::RepositoryChoice)
                    .sortedWith(compareBy({ it.serverName }, { it.repositoryName }))
                repositoryModel.removeAllElements()
                repositories.forEach(repositoryModel::addElement)
                val selectedIndex = repositories.indexOfFirst { it.key == previous }.takeIf { it >= 0 } ?: 0
                if (repositories.isNotEmpty()) {
                    repositoryBox.selectedIndex = selectedIndex
                    refreshPipelines()
                } else {
                    clearTree()
                    status.text = "Map a local Git root to a Bosca repository on the Overview tab"
                }
            }
        }
    }

    private fun refreshPipelines(silent: Boolean = false) {
        val repository = selectedRepository() ?: return
        if (project.isDisposed || refreshing) return
        refreshing = true
        refreshButton.isEnabled = false
        if (!silent) status.text = "Loading ${repository.repositoryName} pipelines…"
        val selectedKey = pendingRunId?.let { "run:$it" } ?: selectedNode()?.key
        pipelines.pipelines(repository.serverProfileId, repository.repositoryId).whenComplete { values, error ->
            onUiThread {
                refreshing = false
                refreshButton.isEnabled = true
                if (error != null) {
                    showError("Pipeline refresh failed", error)
                    return@onUiThread
                }
                populateTree(values, selectedKey)
                if ((selectedNode() as? RunNode)?.run?.id == pendingRunId) pendingRunId = null
                val active = values.sumOf { pipeline -> pipeline.runs.count { it.status.isPipelineActive() } }
                status.text = "${values.size} pipeline(s), $active active · ${repository.serverName}"
            }
        }
    }

    private fun populateTree(values: List<BoscaPipeline>, selectedKey: String?) {
        root.removeAllChildren()
        values.sortedBy { it.name }.forEach { pipeline ->
            val pipelineNode = DefaultMutableTreeNode(PipelineNode(pipeline))
            pipeline.runs.sortedByDescending { it.number }.forEach { run ->
                val runNode = DefaultMutableTreeNode(RunNode(pipeline, run))
                run.jobs.forEach { job ->
                    val jobNode = DefaultMutableTreeNode(JobNode(pipeline, run, job))
                    job.steps.sortedBy { it.ordinal }.forEach { step ->
                        jobNode.add(DefaultMutableTreeNode(StepNode(pipeline, run, job, step)))
                    }
                    runNode.add(jobNode)
                }
                pipelineNode.add(runNode)
            }
            root.add(pipelineNode)
        }
        treeModel.reload()
        restoreSelection(selectedKey)
        if (tree.selectionPath == null && root.childCount > 0) {
            tree.selectionPath = TreePath((root.firstChild as DefaultMutableTreeNode).path)
        }
    }

    private fun restoreSelection(key: String?) {
        if (key == null) return
        val found = root.depthFirstEnumeration().asSequence()
            .mapNotNull { it as? DefaultMutableTreeNode }
            .firstOrNull { (it.userObject as? PipelineTreeNode)?.key == key }
        if (found != null) {
            tree.selectionPath = TreePath(found.path)
            tree.scrollPathToVisible(TreePath(found.path))
        }
    }

    private fun selectionChanged(@Suppress("UNUSED_PARAMETER") event: TreeSelectionEvent) {
        logSubscription?.close()
        logSubscription = null
        logs.text = ""
        val node = selectedNode()
        details.text = node?.details().orEmpty()
        details.caretPosition = 0
        updateActions()
        if (node is StepNode) loadLogs(node)
    }

    private fun loadLogs(node: StepNode) {
        logs.text = "Loading log…"
        pipelines.logs(node.pipeline.serverProfileId, node.step.id).whenComplete { lines, error ->
            onUiThread {
                if ((selectedNode() as? StepNode)?.key != node.key) return@onUiThread
                if (error != null) {
                    logs.text = "Unable to load log: ${rootMessage(error)}"
                    return@onUiThread
                }
                logs.text = lines.joinToString("\n") { it.render() }
                logs.caretPosition = logs.document.length
                if (node.step.status.isPipelineActive()) subscribeToLogs(node)
            }
        }
    }

    private fun subscribeToLogs(node: StepNode) {
        logSubscription = runCatching {
            pipelines.subscribeLogs(
                serverProfileId = node.pipeline.serverProfileId,
                repositoryId = node.pipeline.repositoryId,
                stepId = node.step.id,
                onNext = { line -> onUiThread { appendLog(node.key, line) } },
                onError = { error -> onUiThread { status.text = "Live log disconnected: ${rootMessage(error)}" } },
            )
        }.onFailure { status.text = "Live log unavailable: ${rootMessage(it)}" }.getOrNull()
    }

    private fun appendLog(stepKey: String, line: BoscaPipelineLogLine) {
        if ((selectedNode() as? StepNode)?.key != stepKey) return
        if (logs.text.isNotEmpty()) logs.append("\n")
        logs.append(line.render())
        logs.caretPosition = logs.document.length
    }

    private fun triggerSelectedPipeline() {
        val repository = selectedRepository() ?: return
        val pipeline = when (val node = selectedNode()) {
            is PipelineNode -> node.pipeline
            is RunNode -> node.pipeline
            is JobNode -> node.pipeline
            is StepNode -> node.pipeline
            else -> return
        }
        val ref = Messages.showInputDialog(
            project,
            "Git ref to run:",
            "Run ${pipeline.name}",
            null,
            repository.defaultBranch,
            null,
        )?.trim()?.takeIf { it.isNotEmpty() } ?: return
        perform("Starting ${pipeline.name}", pipelines.trigger(pipeline.serverProfileId, pipeline.id, ref))
    }

    private fun cancelSelection() {
        when (val node = selectedNode()) {
            is RunNode -> perform("Cancelling run #${node.run.number}", pipelines.cancelRun(node.pipeline.serverProfileId, node.run.id))
            is JobNode -> perform("Cancelling ${node.job.name}", pipelines.cancelJob(node.pipeline.serverProfileId, node.job.id))
            else -> Unit
        }
    }

    private fun rerunSelection() {
        when (val node = selectedNode()) {
            is RunNode -> perform("Rerunning #${node.run.number}", pipelines.rerun(node.pipeline.serverProfileId, node.run.id))
            is JobNode -> perform("Rerunning ${node.job.name}", pipelines.rerunJob(node.pipeline.serverProfileId, node.job.id))
            else -> Unit
        }
    }

    private fun rerunFailedSelection() {
        val node = selectedNode() as? RunNode ?: return
        perform("Rerunning failed jobs in #${node.run.number}", pipelines.rerunFailed(node.pipeline.serverProfileId, node.run.id))
    }

    private fun decideApproval(approve: Boolean) {
        val node = selectedNode() as? JobNode ?: return
        val verb = if (approve) "Approve" else "Reject"
        val comment = Messages.showInputDialog(
            project,
            "Optional comment:",
            "$verb ${node.job.name}",
            null,
        )
        if (comment == null) return
        val operation = if (approve) {
            pipelines.approveJob(node.pipeline.serverProfileId, node.job.id, comment.ifBlank { null })
        } else {
            pipelines.rejectJob(node.pipeline.serverProfileId, node.job.id, comment.ifBlank { null })
        }
        perform("${verb}ing ${node.job.name}", operation)
    }

    private fun perform(label: String, operation: CompletableFuture<Boolean>) {
        status.text = "$label…"
        setControlsEnabled(false)
        operation.whenComplete { _, error ->
            onUiThread {
                if (error != null) {
                    showError("Operation failed", error)
                    updateActions()
                } else {
                    status.text = "$label succeeded"
                    refreshing = false
                    refreshPipelines(silent = true)
                }
            }
        }
    }

    private fun updateActions() {
        val node = selectedNode()
        triggerButton.isEnabled = node is PipelineNode || node is RunNode || node is JobNode || node is StepNode
        cancelButton.isEnabled = (node is RunNode && node.run.status.isPipelineActive()) ||
            (node is JobNode && node.job.status.isPipelineActive())
        rerunButton.isEnabled = node is RunNode || node is JobNode
        rerunFailedButton.isEnabled = node is RunNode && node.run.jobs.any { it.status == "FAILURE" }
        approveButton.isEnabled = node is JobNode && node.job.awaitingApproval
        rejectButton.isEnabled = node is JobNode && node.job.awaitingApproval
    }

    private fun setControlsEnabled(enabled: Boolean) {
        triggerButton.isEnabled = enabled
        cancelButton.isEnabled = enabled
        rerunButton.isEnabled = enabled
        rerunFailedButton.isEnabled = enabled
        approveButton.isEnabled = enabled
        rejectButton.isEnabled = enabled
    }

    private fun selectedRepository(): RepositoryChoice? = repositoryBox.selectedItem as? RepositoryChoice

    private fun navigate(target: BoscaNavigationTarget.Pipeline) {
        pendingRunId = target.runId
        val index = (0 until repositoryModel.size).firstOrNull { position ->
            repositoryModel.getElementAt(position).let {
                it.serverProfileId == target.serverProfileId && it.repositoryId == target.repositoryId
            }
        }
        if (index != null) {
            repositoryBox.selectedIndex = index
            refreshPipelines()
        } else {
            refreshRepositories()
        }
    }

    private fun selectedNode(): PipelineTreeNode? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? PipelineTreeNode

    private fun clearTree() {
        logSubscription?.close()
        logSubscription = null
        root.removeAllChildren()
        treeModel.reload()
        details.text = ""
        logs.text = ""
        updateActions()
    }

    private fun showError(prefix: String, error: Throwable) {
        status.text = "$prefix: ${rootMessage(error)}"
    }

    private fun onUiThread(block: () -> Unit) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) block()
        }
    }

    private fun titledPanel(title: String, component: javax.swing.JComponent): JPanel =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(6)
            add(JBLabel(title).apply { border = JBUI.Borders.empty(4) }, BorderLayout.NORTH)
            add(component, BorderLayout.CENTER)
        }

    private fun rootMessage(error: Throwable): String {
        var current = error
        while (current.cause != null) current = current.cause ?: break
        return current.message ?: current.javaClass.simpleName
    }

    override fun dispose() {
        pollTimer.stop()
        logSubscription?.close()
        logSubscription = null
    }

    private data class RepositoryChoice(
        val serverProfileId: String,
        val repositoryId: String,
        val repositoryName: String,
        val defaultBranch: String,
        val serverName: String = BoscaServerRegistry.getInstance().profile(serverProfileId)?.name ?: serverProfileId,
    ) {
        constructor(repository: BoscaRemoteRepository) : this(
            repository.serverProfileId,
            repository.id,
            repository.name,
            repository.defaultBranch,
        )

        val key: String get() = "$serverProfileId:$repositoryId"
        override fun toString(): String = if (serverName.isBlank()) repositoryName else "$serverName · $repositoryName"
    }

    private sealed interface PipelineTreeNode {
        val key: String
        fun details(): String
    }

    private data class PipelineNode(val pipeline: BoscaPipeline) : PipelineTreeNode {
        override val key = "pipeline:${pipeline.id}"
        override fun toString() = pipeline.name
        override fun details() = buildString {
            appendLine(pipeline.name)
            appendLine(pipeline.filePath)
            append("Triggers: ${pipeline.triggerTypes.joinToString()}")
        }
    }

    private data class RunNode(val pipeline: BoscaPipeline, val run: BoscaPipelineRun) : PipelineTreeNode {
        override val key = "run:${run.id}"
        override fun toString() = "#${run.number}  ${run.status}  ${run.ref}"
        override fun details() = buildString {
            appendLine("Run #${run.number} · ${run.status}")
            appendLine("Ref: ${run.ref}")
            appendLine("Commit: ${run.commitSha}")
            appendLine("Trigger: ${run.triggerType}")
            appendLine("Created: ${run.created}")
            run.durationSeconds?.let { appendLine("Duration: ${it}s") }
            if (run.artifacts.isNotEmpty()) {
                appendLine()
                appendLine("Artifacts")
                run.artifacts.forEach { appendLine("• ${it.name} (${it.sizeBytes} bytes)") }
            }
        }.trimEnd()
    }

    private data class JobNode(
        val pipeline: BoscaPipeline,
        val run: BoscaPipelineRun,
        val job: BoscaPipelineJob,
    ) : PipelineTreeNode {
        override val key = "job:${job.id}"
        override fun toString() = "${job.name}  ${job.status}"
        override fun details() = buildString {
            appendLine("${job.name} · ${job.status}")
            appendLine("Runner: ${job.runnerLabel}")
            appendLine("Attempt: ${job.attempt}")
            if (job.awaitingRequirements) appendLine("Waiting for requirements")
            if (job.awaitingApproval) appendLine("Waiting for approval")
            job.errorMessage?.let { appendLine("Error: $it") }
        }.trimEnd()
    }

    private data class StepNode(
        val pipeline: BoscaPipeline,
        val run: BoscaPipelineRun,
        val job: BoscaPipelineJob,
        val step: BoscaPipelineStep,
    ) : PipelineTreeNode {
        override val key = "step:${step.id}"
        override fun toString() = "${step.ordinal}. ${step.name}  ${step.status}"
        override fun details() = buildString {
            appendLine("${step.name} · ${step.status}")
            step.exitCode?.let { appendLine("Exit code: $it") }
            step.started?.let { appendLine("Started: $it") }
            step.finished?.let { appendLine("Finished: $it") }
            step.errorMessage?.let { appendLine("Error: $it") }
        }.trimEnd()
    }

    private fun BoscaPipelineLogLine.render(): String = "[$timestamp]${if (stream == "STDERR") " [stderr]" else ""} $content"

    private fun <T> java.util.Enumeration<T>.asSequence(): Sequence<T> = sequence {
        while (hasMoreElements()) yield(nextElement())
    }
}
