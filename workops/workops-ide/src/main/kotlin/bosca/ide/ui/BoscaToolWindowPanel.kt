package bosca.ide.ui

import bosca.ide.project.BoscaProjectSettings
import bosca.ide.repository.BoscaDiscoverySnapshot
import bosca.ide.repository.BoscaMappingStatus
import bosca.ide.repository.BoscaRepositoryDiscoveryService
import bosca.ide.repository.BoscaRootDiscovery
import bosca.ide.repository.BoscaServerDiscovery
import bosca.ide.server.BoscaServerRegistry
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

internal class BoscaToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val registry = BoscaServerRegistry.getInstance()
    private val projectSettings = project.getService(BoscaProjectSettings::class.java)
    private val discovery = project.getService(BoscaRepositoryDiscoveryService::class.java)
    private val status = JBLabel("Configure a Bosca server to get started")
    private val serverModel = ServerTableModel()
    private val rootModel = RootTableModel()
    private val serverTable = JBTable(serverModel)
    private val rootTable = JBTable(rootModel)
    private val refreshButton = JButton("Refresh")
    private val mapButton = JButton("Map Repository…")
    private val unmapButton = JButton("Remove Mapping")
    private var snapshot: BoscaDiscoverySnapshot? = null

    init {
        border = JBUI.Borders.empty(6)
        serverTable.setShowGrid(false)
        serverTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        rootTable.setShowGrid(false)
        rootTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        rootTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) mapSelectedRoot()
            }
        })

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(refreshButton.apply { addActionListener { refresh() } })
            add(JButton("Manage Servers…").apply { addActionListener { manageServers() } })
            add(JButton("Servers for Project…").apply { addActionListener { chooseEnabledServers() } })
            add(mapButton.apply { addActionListener { mapSelectedRoot() } })
            add(unmapButton.apply { addActionListener { unmapSelectedRoot() } })
        }
        add(JPanel(BorderLayout()).apply {
            add(buttons, BorderLayout.WEST)
            add(status, BorderLayout.CENTER)
        }, BorderLayout.NORTH)

        val splitter = OnePixelSplitter(true, 0.34f).apply {
            firstComponent = titledPanel("Bosca servers", JBScrollPane(serverTable))
            secondComponent = titledPanel("Local Git repository mappings", JBScrollPane(rootTable))
        }
        add(splitter, BorderLayout.CENTER)

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(BoscaServerRegistry.PROFILES_CHANGED, BoscaServerRegistry.Listener { refresh() })
        refresh()
    }

    private fun refresh() {
        if (project.isDisposed) return
        refreshButton.isEnabled = false
        mapButton.isEnabled = false
        unmapButton.isEnabled = false
        status.text = "Refreshing Bosca servers and repositories…"
        discovery.refresh().whenComplete { result, error ->
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                refreshButton.isEnabled = true
                if (error != null) {
                    status.text = "Refresh failed: ${rootMessage(error)}"
                    return@invokeLater
                }
                snapshot = result
                serverModel.setRows(result.servers)
                rootModel.setRows(result.roots)
                mapButton.isEnabled = result.roots.isNotEmpty()
                unmapButton.isEnabled = result.roots.isNotEmpty()
                val failures = result.servers.count { it.error != null }
                status.text = when {
                    result.servers.isEmpty() -> "No Bosca servers are enabled for this project"
                    failures == 0 -> "Connected to ${result.servers.size} Bosca server(s)"
                    else -> "${result.servers.size - failures} connected; $failures unavailable"
                }
            }
        }
    }

    private fun manageServers() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "bosca.servers")
        refresh()
    }

    private fun chooseEnabledServers() {
        if (BoscaEnabledServersDialog(project).showAndGet()) refresh()
    }

    private fun mapSelectedRoot() {
        val current = snapshot ?: return
        val row = rootTable.selectedRow.takeIf { it >= 0 } ?: return
        val root = rootModel.row(rootTable.convertRowIndexToModel(row))
        val dialog = BoscaRepositoryMappingDialog(project, root.root, current, projectSettings.mapping(root.root.rootUrl))
        if (!dialog.showAndGet()) return
        projectSettings.putMapping(dialog.mapping())
        refresh()
    }

    private fun unmapSelectedRoot() {
        val row = rootTable.selectedRow.takeIf { it >= 0 } ?: return
        val root = rootModel.row(rootTable.convertRowIndexToModel(row))
        projectSettings.removeMapping(root.root.rootUrl)
        refresh()
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

    override fun dispose() = Unit

    private class ServerTableModel : AbstractTableModel() {
        private var rows = emptyList<BoscaServerDiscovery>()
        private val columns = arrayOf("Server", "Endpoint", "Connection", "Repositories")

        fun setRows(rows: List<BoscaServerDiscovery>) {
            this.rows = rows
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
            0 -> rows[rowIndex].profile.name
            1 -> rows[rowIndex].profile.graphqlEndpoint
            2 -> rows[rowIndex].error ?: "Connected"
            else -> rows[rowIndex].repositories.size
        }
    }

    private class RootTableModel : AbstractTableModel() {
        private var rows = emptyList<BoscaRootDiscovery>()
        private val columns = arrayOf("Local root", "Remote", "Bosca server", "Bosca repository", "Status")

        fun setRows(rows: List<BoscaRootDiscovery>) {
            this.rows = rows
            fireTableDataChanged()
        }

        fun row(index: Int): BoscaRootDiscovery = rows[index]

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.root.name
                1 -> row.root.remoteUrls.firstOrNull().orEmpty()
                2 -> row.repository?.serverProfileId?.let(::serverName).orEmpty()
                3 -> row.repository?.name.orEmpty()
                else -> row.status.displayName()
            }
        }

        private fun serverName(profileId: String): String =
            BoscaServerRegistry.getInstance().profile(profileId)?.name ?: profileId

        private fun BoscaMappingStatus.displayName(): String = when (this) {
            BoscaMappingStatus.MAPPED -> "Mapped"
            BoscaMappingStatus.AUTO_MAPPED -> "Auto-mapped"
            BoscaMappingStatus.AMBIGUOUS -> "Choose a server/repository"
            BoscaMappingStatus.UNMAPPED -> "Unmapped"
            BoscaMappingStatus.SERVER_DISABLED -> "Mapped server disabled"
            BoscaMappingStatus.SERVER_UNAVAILABLE -> "Server unavailable"
            BoscaMappingStatus.REPOSITORY_NOT_FOUND -> "Mapped repository not found"
        }
    }
}
