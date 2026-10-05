package bosca.ide.ui

import bosca.ide.project.BoscaProjectSettings
import bosca.ide.server.BoscaServerProfile
import bosca.ide.server.BoscaServerRegistry
import bosca.ide.workops.BoscaWorkOpsBundle
import bosca.ide.workops.BoscaWorkOpsDocument
import bosca.ide.workops.BoscaWorkOpsProject
import bosca.ide.workops.BoscaWorkOpsRequirement
import bosca.ide.workops.BoscaWorkOpsResolution
import bosca.ide.workops.BoscaWorkOpsService
import bosca.ide.workops.BoscaWorkOpsSpec
import bosca.ide.workops.BoscaWorkOpsTask
import bosca.ide.workops.BoscaWorkOpsTransition
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ColoredListCellRenderer
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
import java.util.concurrent.CompletableFuture
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/** Project issues, specifications, requirements, comments, documents, and workflow actions. */
internal class BoscaWorkOpsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val registry = BoscaServerRegistry.getInstance()
    private val projectSettings = project.getService(BoscaProjectSettings::class.java)
    private val service = project.getService(BoscaWorkOpsService::class.java)
    private val serverModel = DefaultComboBoxModel<ServerChoice>()
    private val projectModel = DefaultComboBoxModel<ProjectChoice>()
    private val serverBox = JComboBox(serverModel)
    private val projectBox = JComboBox(projectModel)
    private val status = JBLabel("Select a Bosca server and WorkOps project")
    private val refreshButton = JButton("Refresh")
    private val tabs = JBTabbedPane()
    private val taskView = TaskView()
    private val specView = SpecView()
    private var bundle = BoscaWorkOpsBundle(emptyList(), emptyList(), emptyList(), emptyList())
    private var loading = false

    init {
        border = JBUI.Borders.empty(6)
        serverBox.prototypeDisplayValue = ServerChoice("", "A reasonably long Bosca server")
        projectBox.prototypeDisplayValue = ProjectChoice("", "", "WORKOPS", "A reasonably long project")
        serverBox.addActionListener { loadProjects() }
        projectBox.addActionListener { loadBundle() }
        refreshButton.addActionListener { loadBundle() }

        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(JBLabel("Server:"))
                add(serverBox)
                add(JBLabel("Project:"))
                add(projectBox)
                add(refreshButton)
            }, BorderLayout.WEST)
            add(status, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        tabs.addTab("Issues", taskView)
        tabs.addTab("Specifications", specView)
        add(tabs, BorderLayout.CENTER)

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(BoscaServerRegistry.PROFILES_CHANGED, BoscaServerRegistry.Listener { refreshServers() })
        refreshServers()
    }

    private fun refreshServers() {
        val previous = selectedServer()?.id
        val profiles = registry.profiles()
        val enabled = projectSettings.effectiveProfileIds(profiles.map { it.id })
        val choices = profiles.filter { it.id in enabled }.map(::ServerChoice)
        serverModel.removeAllElements()
        choices.forEach(serverModel::addElement)
        if (choices.isNotEmpty()) {
            serverBox.selectedIndex = choices.indexOfFirst { it.id == previous }.takeIf { it >= 0 } ?: 0
            loadProjects()
        } else {
            projectModel.removeAllElements()
            clearViews()
            status.text = "Enable a Bosca server for this project on the Overview tab"
        }
    }

    private fun loadProjects() {
        val server = selectedServer() ?: return
        if (loading) return
        loading = true
        setBusy("Loading WorkOps projects from ${server.name}…")
        val previous = selectedProject()?.id
        service.projects(server.id).whenComplete { projects, error ->
            onUiThread {
                loading = false
                refreshButton.isEnabled = true
                if (error != null) {
                    showError("Project loading failed", error)
                    return@onUiThread
                }
                val choices = projects.map(::ProjectChoice)
                projectModel.removeAllElements()
                choices.forEach(projectModel::addElement)
                if (choices.isNotEmpty()) {
                    projectBox.selectedIndex = choices.indexOfFirst { it.id == previous }.takeIf { it >= 0 } ?: 0
                    loadBundle()
                } else {
                    clearViews()
                    status.text = "No WorkOps projects are visible on ${server.name}"
                }
            }
        }
    }

    private fun loadBundle() {
        val server = selectedServer() ?: return
        val selectedProject = selectedProject() ?: return
        if (loading) return
        loading = true
        setBusy("Loading ${selectedProject.key} issues and specifications…")
        val taskKey = taskView.selected()?.key
        val specKey = specView.selectedKey()
        service.bundle(server.id, selectedProject.id).whenComplete { result, error ->
            onUiThread {
                loading = false
                refreshButton.isEnabled = true
                if (error != null) {
                    showError("WorkOps refresh failed", error)
                    return@onUiThread
                }
                bundle = result
                taskView.setTasks(result.tasks, taskKey)
                specView.setSpecs(result.specs, specKey)
                status.text = "${result.tasks.size} issue(s), ${result.specs.size} specification(s) · ${server.name}"
            }
        }
    }

    private fun perform(label: String, operation: CompletableFuture<Boolean>) {
        setBusy("$label…")
        operation.whenComplete { _, error ->
            onUiThread {
                loading = false
                refreshButton.isEnabled = true
                if (error != null) {
                    val message = rootMessage(error)
                    status.text = if (message.contains("OPTIMISTIC_LOCK", ignoreCase = true)) {
                        "$label conflicted with a newer server version; refreshing…"
                    } else {
                        "$label failed: $message"
                    }
                    loadBundle()
                } else {
                    status.text = "$label succeeded"
                    loadBundle()
                }
            }
        }
    }

    private fun chooseTransition(transitions: List<BoscaWorkOpsTransition>): BoscaWorkOpsTransition? {
        if (transitions.isEmpty()) {
            status.text = "No workflow transition is currently available"
            return null
        }
        return javax.swing.JOptionPane.showInputDialog(
            this,
            "Choose a workflow transition:",
            "Transition",
            javax.swing.JOptionPane.PLAIN_MESSAGE,
            null,
            transitions.toTypedArray(),
            transitions.first(),
        ) as? BoscaWorkOpsTransition
    }

    private fun chooseResolution(transition: BoscaWorkOpsTransition): String? {
        if (transition.toCategory != "DONE") return null
        val values = bundle.resolutions
        if (values.isEmpty()) return null
        return (javax.swing.JOptionPane.showInputDialog(
            this,
            "Resolution:",
            transition.name,
            javax.swing.JOptionPane.PLAIN_MESSAGE,
            null,
            values.toTypedArray(),
            values.first(),
        ) as? BoscaWorkOpsResolution)?.id
    }

    private fun prompt(label: String, initial: String = ""): String? =
        Messages.showInputDialog(project, label, "Bosca WorkOps", null, initial, null)
            ?.trim()?.takeIf { it.isNotEmpty() }

    private fun selectedServer(): ServerChoice? = serverBox.selectedItem as? ServerChoice
    private fun selectedProject(): ProjectChoice? = projectBox.selectedItem as? ProjectChoice

    private fun setBusy(text: String) {
        loading = true
        refreshButton.isEnabled = false
        status.text = text
    }

    private fun clearViews() {
        bundle = BoscaWorkOpsBundle(emptyList(), emptyList(), emptyList(), emptyList())
        taskView.setTasks(emptyList(), null)
        specView.setSpecs(emptyList(), null)
    }

    private fun showError(prefix: String, error: Throwable) {
        status.text = "$prefix: ${rootMessage(error)}"
    }

    private fun onUiThread(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) block()
        }
    }

    private fun rootMessage(error: Throwable): String {
        var current = error
        while (current.cause != null) current = current.cause ?: break
        return current.message ?: current.javaClass.simpleName
    }

    override fun dispose() = Unit

    private inner class TaskView : JPanel(BorderLayout()) {
        private val allTasks = mutableListOf<BoscaWorkOpsTask>()
        private val listModel = DefaultListModel<BoscaWorkOpsTask>()
        private val list = JBList(listModel)
        private val filter = JBTextField()
        private val summary = JBTextField()
        private val assignee = JBTextField()
        private val description = JBTextArea()
        private val detail = JBLabel("No issue selected")
        private val comments = JBTextArea()
        private val save = JButton("Save")
        private val transition = JButton("Transition…")
        private val comment = JButton("Comment…")
        private val link = JButton("Link Issue…")

        init {
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION
            list.cellRenderer = object : ColoredListCellRenderer<BoscaWorkOpsTask>() {
                override fun customizeCellRenderer(
                    list: JList<out BoscaWorkOpsTask>,
                    value: BoscaWorkOpsTask,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    append(value.key)
                    append("  ${value.status}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(value.summary)
                }
            }
            list.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
            filter.emptyText.text = "Filter by key, summary, status, or assignee"
            filter.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(event: DocumentEvent) = applyFilter()
                override fun removeUpdate(event: DocumentEvent) = applyFilter()
                override fun changedUpdate(event: DocumentEvent) = applyFilter()
            })
            description.lineWrap = true
            description.wrapStyleWord = true
            comments.isEditable = false
            comments.lineWrap = true
            comments.wrapStyleWord = true

            val left = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyRight(6)
                add(filter, BorderLayout.NORTH)
                add(JBScrollPane(list), BorderLayout.CENTER)
                add(JButton("New Issue…").apply { addActionListener { createTask() } }, BorderLayout.SOUTH)
            }
            val editor = JPanel(BorderLayout()).apply {
                add(JPanel(BorderLayout()).apply {
                    add(detail, BorderLayout.NORTH)
                    add(JPanel(BorderLayout()).apply {
                        add(summary, BorderLayout.CENTER)
                        add(assignee, BorderLayout.EAST)
                    }, BorderLayout.SOUTH)
                }, BorderLayout.NORTH)
                add(OnePixelSplitter(true, 0.62f).apply {
                    firstComponent = titled("Markdown description", JBScrollPane(description))
                    secondComponent = titled("Comments", JBScrollPane(comments))
                }, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                    add(save.apply { addActionListener { saveTask() } })
                    add(transition.apply { addActionListener { transitionTask() } })
                    add(comment.apply { addActionListener { commentTask() } })
                    add(link.apply { addActionListener { linkTask() } })
                }, BorderLayout.SOUTH)
            }
            add(OnePixelSplitter(false, 0.36f).apply {
                firstComponent = left
                secondComponent = editor
            }, BorderLayout.CENTER)
            showSelected()
        }

        fun setTasks(tasks: List<BoscaWorkOpsTask>, selectedKey: String?) {
            allTasks.clear()
            allTasks.addAll(tasks)
            applyFilter(selectedKey)
        }

        fun selected(): BoscaWorkOpsTask? = list.selectedValue

        private fun applyFilter(selectedKey: String? = selected()?.key) {
            val query = filter.text.trim().lowercase()
            val values = allTasks.filter { task ->
                query.isEmpty() || listOf(task.key, task.summary, task.status, task.assigneeName.orEmpty())
                    .any { query in it.lowercase() }
            }
            listModel.removeAllElements()
            values.forEach(listModel::addElement)
            val selectedIndex = values.indexOfFirst { it.key == selectedKey }
            if (values.isNotEmpty()) list.selectedIndex = if (selectedIndex >= 0) selectedIndex else 0 else showSelected()
        }

        private fun showSelected() {
            val task = selected()
            detail.text = task?.let { "${it.key} · ${it.taskType} · ${it.status} · ${it.priority}" } ?: "No issue selected"
            summary.text = task?.summary.orEmpty()
            assignee.text = task?.assigneeProfileId.orEmpty()
            assignee.toolTipText = "Assignee profile UUID; clear to unassign"
            description.text = task?.descriptionMarkdown.orEmpty()
            description.caretPosition = 0
            comments.text = task?.let { value ->
                buildString {
                    if (value.comments.isNotEmpty()) {
                        appendLine("Comments")
                        appendLine(value.comments.joinToString("\n\n") { "${it.author} · ${it.created}\n${it.content}" })
                    }
                    if (value.links.isNotEmpty()) {
                        if (isNotEmpty()) appendLine()
                        appendLine("Links")
                        value.links.forEach { appendLine("• $it") }
                    }
                    if (value.history.isNotEmpty()) {
                        if (isNotEmpty()) appendLine()
                        appendLine("Recent history")
                        value.history.forEach { appendLine("• $it") }
                    }
                }.trimEnd()
            }.orEmpty()
            comments.caretPosition = 0
            save.isEnabled = task != null
            transition.isEnabled = task?.transitions?.isNotEmpty() == true
            comment.isEnabled = task != null
            link.isEnabled = task != null && bundle.linkTypes.isNotEmpty()
        }

        private fun createTask() {
            val targetProject = selectedProject() ?: return
            val server = selectedServer() ?: return
            val title = prompt("Issue summary:") ?: return
            val body = Messages.showMultilineInputDialog(project, "Markdown description:", "New $title", "", null, null) ?: return
            perform("Creating issue", service.createTask(server.id, targetProject.id, title, body))
        }

        private fun saveTask() {
            val task = selected() ?: return
            val title = summary.text.trim().takeIf { it.isNotEmpty() } ?: return
            perform("Saving ${task.key}", service.updateTask(task.serverProfileId, task, title, description.text, assignee.text.trim().ifEmpty { null }))
        }

        private fun transitionTask() {
            val task = selected() ?: return
            val selectedTransition = chooseTransition(task.transitions) ?: return
            val resolution = chooseResolution(selectedTransition)
            val transitionComment = prompt("Optional transition comment:", "")
            perform("Transitioning ${task.key}", service.transitionTask(task.serverProfileId, task, selectedTransition, resolution, transitionComment))
        }

        private fun commentTask() {
            val task = selected() ?: return
            val body = Messages.showMultilineInputDialog(project, "Comment:", "Comment on ${task.key}", "", null, null)
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return
            perform("Commenting on ${task.key}", service.commentTask(task.serverProfileId, task.id, body))
        }

        private fun linkTask() {
            val task = selected() ?: return
            val linkType = javax.swing.JOptionPane.showInputDialog(
                this,
                "Relationship:",
                "Link ${task.key}",
                javax.swing.JOptionPane.PLAIN_MESSAGE,
                null,
                bundle.linkTypes.toTypedArray(),
                bundle.linkTypes.firstOrNull(),
            ) as? bosca.ide.workops.BoscaWorkOpsLinkType ?: return
            val targetKey = prompt("Target issue key:") ?: return
            perform("Linking ${task.key}", service.linkTask(task.serverProfileId, task, linkType, targetKey.uppercase()))
        }
    }

    private inner class SpecView : JPanel(BorderLayout()) {
        private val root = DefaultMutableTreeNode("Specifications")
        private val treeModel = DefaultTreeModel(root)
        private val tree = Tree(treeModel)
        private val titleField = JBTextField()
        private val body = JBTextArea()
        private val detail = JBLabel("No specification selected")
        private val comments = JBTextArea()
        private val save = JButton("Save Document")
        private val transition = JButton("Transition…")
        private val comment = JButton("Comment…")
        private val newRequirement = JButton("New Requirement…")
        private var loadedDocument: BoscaWorkOpsDocument? = null

        init {
            tree.isRootVisible = false
            tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            tree.addTreeSelectionListener { showSelected() }
            body.lineWrap = true
            body.wrapStyleWord = true
            comments.isEditable = false
            comments.lineWrap = true
            comments.wrapStyleWord = true
            add(OnePixelSplitter(false, 0.36f).apply {
                firstComponent = JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.emptyRight(6)
                    add(JBScrollPane(tree), BorderLayout.CENTER)
                    add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                        add(JButton("New Spec…").apply { addActionListener { createSpec() } })
                        add(newRequirement.apply { addActionListener { createRequirement() } })
                    }, BorderLayout.SOUTH)
                }
                secondComponent = JPanel(BorderLayout()).apply {
                    add(JPanel(BorderLayout()).apply {
                        add(detail, BorderLayout.NORTH)
                        add(titleField, BorderLayout.SOUTH)
                    }, BorderLayout.NORTH)
                    add(OnePixelSplitter(true, 0.72f).apply {
                        firstComponent = titled("Markdown document", JBScrollPane(body))
                        secondComponent = titled("Comments", JBScrollPane(comments))
                    }, BorderLayout.CENTER)
                    add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                        add(save.apply { addActionListener { saveDocument() } })
                        add(transition.apply { addActionListener { transitionSelected() } })
                        add(comment.apply { addActionListener { commentSelected() } })
                    }, BorderLayout.SOUTH)
                }
            }, BorderLayout.CENTER)
            showSelected()
        }

        fun setSpecs(specs: List<BoscaWorkOpsSpec>, selectedKey: String?) {
            root.removeAllChildren()
            specs.forEach { spec ->
                val parent = DefaultMutableTreeNode(SpecNode(spec))
                spec.requirements.forEach { parent.add(DefaultMutableTreeNode(RequirementNode(spec, it))) }
                root.add(parent)
            }
            treeModel.reload()
            restoreSelection(selectedKey)
            if (tree.selectionPath == null && root.childCount > 0) tree.selectionPath = TreePath((root.firstChild as DefaultMutableTreeNode).path)
        }

        fun selectedKey(): String? = selectedNode()?.key

        private fun selectedNode(): WorkOpsTreeNode? =
            (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? WorkOpsTreeNode

        private fun restoreSelection(key: String?) {
            if (key == null) return
            val values = root.depthFirstEnumeration()
            while (values.hasMoreElements()) {
                val node = values.nextElement() as? DefaultMutableTreeNode ?: continue
                if ((node.userObject as? WorkOpsTreeNode)?.key == key) {
                    tree.selectionPath = TreePath(node.path)
                    return
                }
            }
        }

        private fun showSelected() {
            loadedDocument = null
            when (val node = selectedNode()) {
                is SpecNode -> {
                    val spec = node.spec
                    loadedDocument = BoscaWorkOpsDocument(spec.metadataId, spec.metadataVersion, spec.name, spec.markdown)
                    detail.text = "${spec.key} · ${spec.status} · owner ${spec.ownerName ?: "Unassigned"}"
                    titleField.text = spec.name
                    body.text = spec.markdown
                    comments.text = buildString {
                        if (spec.comments.isNotEmpty()) {
                            appendLine("Comments")
                            appendLine(spec.comments.joinToString("\n\n") { "${it.author} · ${it.created}\n${it.content}" })
                        }
                        if (spec.history.isNotEmpty()) {
                            if (isNotEmpty()) appendLine()
                            appendLine("Recent history")
                            spec.history.forEach { appendLine("• $it") }
                        }
                    }.trimEnd()
                    transition.isEnabled = spec.transitions.isNotEmpty()
                    comment.isEnabled = true
                    newRequirement.isEnabled = true
                    save.isEnabled = true
                }
                is RequirementNode -> {
                    val requirement = node.requirement
                    detail.text = "${requirement.key} · ${requirement.status} · ${requirement.priority}"
                    titleField.text = requirement.key
                    body.text = "Loading document…"
                    comments.text = "Requirement comments are added here and retained on the server."
                    transition.isEnabled = requirement.transitions.isNotEmpty()
                    comment.isEnabled = true
                    newRequirement.isEnabled = true
                    save.isEnabled = false
                    service.document(requirement.serverProfileId, requirement.metadataId).whenComplete { document, error ->
                        onUiThread {
                            if ((selectedNode() as? RequirementNode)?.key != node.key) return@onUiThread
                            if (error != null) {
                                body.text = "Unable to load document: ${rootMessage(error)}"
                                return@onUiThread
                            }
                            loadedDocument = document
                            titleField.text = document.title
                            body.text = document.markdown
                            body.caretPosition = 0
                            save.isEnabled = true
                        }
                    }
                }
                null -> {
                    detail.text = "No specification selected"
                    titleField.text = ""
                    body.text = ""
                    comments.text = ""
                    save.isEnabled = false
                    transition.isEnabled = false
                    comment.isEnabled = false
                    newRequirement.isEnabled = false
                }
            }
            body.caretPosition = 0
            comments.caretPosition = 0
        }

        private fun createSpec() {
            val server = selectedServer() ?: return
            val targetProject = selectedProject() ?: return
            val title = prompt("Specification name:") ?: return
            perform("Creating specification", service.createSpec(server.id, targetProject.id, title))
        }

        private fun createRequirement() {
            val spec = when (val node = selectedNode()) {
                is SpecNode -> node.spec
                is RequirementNode -> node.spec
                else -> return
            }
            val title = prompt("Requirement name:") ?: return
            perform("Creating requirement", service.createRequirement(spec.serverProfileId, spec.id, title))
        }

        private fun saveDocument() {
            val document = loadedDocument ?: return
            val serverId = when (val node = selectedNode()) {
                is SpecNode -> node.spec.serverProfileId
                is RequirementNode -> node.requirement.serverProfileId
                else -> return
            }
            val title = titleField.text.trim().takeIf { it.isNotEmpty() } ?: return
            perform("Saving $title", service.saveDocument(serverId, document, title, body.text))
        }

        private fun transitionSelected() {
            when (val node = selectedNode()) {
                is SpecNode -> {
                    val selectedTransition = chooseTransition(node.spec.transitions) ?: return
                    perform(
                        "Transitioning ${node.spec.key}",
                        service.transitionSpec(node.spec.serverProfileId, node.spec, selectedTransition, chooseResolution(selectedTransition)),
                    )
                }
                is RequirementNode -> {
                    val selectedTransition = chooseTransition(node.requirement.transitions) ?: return
                    perform(
                        "Transitioning ${node.requirement.key}",
                        service.transitionRequirement(
                            node.requirement.serverProfileId,
                            node.requirement,
                            selectedTransition,
                            chooseResolution(selectedTransition),
                            prompt("Optional transition comment:", ""),
                        ),
                    )
                }
                else -> Unit
            }
        }

        private fun commentSelected() {
            val node = selectedNode() ?: return
            val text = Messages.showMultilineInputDialog(project, "Comment:", "Comment on ${node.key}", "", null, null)
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val operation = when (node) {
                is SpecNode -> service.commentSpec(node.spec.serverProfileId, node.spec.id, text)
                is RequirementNode -> service.commentRequirement(node.requirement.serverProfileId, node.requirement.id, text)
            }
            perform("Commenting on ${node.key}", operation)
        }
    }

    private sealed interface WorkOpsTreeNode {
        val key: String
    }

    private data class SpecNode(val spec: BoscaWorkOpsSpec) : WorkOpsTreeNode {
        override val key = spec.key
        override fun toString() = "${spec.key}  ${spec.status}  ${spec.name}"
    }

    private data class RequirementNode(
        val spec: BoscaWorkOpsSpec,
        val requirement: BoscaWorkOpsRequirement,
    ) : WorkOpsTreeNode {
        override val key = requirement.key
        override fun toString() = "${requirement.key}  ${requirement.status}"
    }

    private data class ServerChoice(val id: String, val name: String) {
        constructor(profile: BoscaServerProfile) : this(profile.id, profile.name)
        override fun toString() = name
    }

    private data class ProjectChoice(
        val serverProfileId: String,
        val id: String,
        val key: String,
        val name: String,
    ) {
        constructor(value: BoscaWorkOpsProject) : this(value.serverProfileId, value.id, value.key, value.name)
        override fun toString() = "$key · $name"
    }

    private fun titled(title: String, component: javax.swing.JComponent): JPanel = JPanel(BorderLayout()).apply {
        add(JBLabel(title).apply { border = JBUI.Borders.empty(4) }, BorderLayout.NORTH)
        add(component, BorderLayout.CENTER)
    }
}
