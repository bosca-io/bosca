package bosca.ide.notification

import bosca.ide.navigation.BoscaNavigationListener
import bosca.ide.navigation.BoscaNavigationTarget
import bosca.ide.project.BoscaProjectSettings
import bosca.ide.repository.BoscaRemoteRepository
import bosca.ide.repository.BoscaRepositoryDiscoveryService
import bosca.ide.server.BoscaServerRegistry
import bosca.ide.server.BoscaSubscription
import com.google.gson.JsonObject
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class BoscaNotificationDeduplicator(
    private val ttlMillis: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val seen = linkedMapOf<String, Long>()

    @Synchronized
    fun firstOccurrence(key: String): Boolean {
        val now = clock()
        seen.entries.removeIf { now - it.value > ttlMillis }
        val previous = seen[key]
        if (previous != null && now - previous <= ttlMillis) return false
        seen[key] = now
        if (seen.size > 2_000) seen.remove(seen.keys.first())
        return true
    }
}

/** Maintains one reconnecting pipeline and PR activity subscription per mapped server/repository pair. */
@Service(Service.Level.PROJECT)
class BoscaNotificationService(private val project: Project) : Disposable {
    private val registry = BoscaServerRegistry.getInstance()
    private val discovery = project.getService(BoscaRepositoryDiscoveryService::class.java)
    private val settings = project.getService(BoscaProjectSettings::class.java)
    private val connections = project.getService(bosca.ide.server.BoscaConnectionManager::class.java)
    private val started = AtomicBoolean(false)
    private val deduplicator = BoscaNotificationDeduplicator()
    private val loops = linkedMapOf<String, SubscriptionLoop>()
    private var reconciliation: ScheduledFuture<*>? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(BoscaServerRegistry.PROFILES_CHANGED, BoscaServerRegistry.Listener { reconcileMappings() })
        reconcileMappings()
        reconciliation = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { reconcileMappings() },
            60,
            60,
            TimeUnit.SECONDS,
        )
    }

    private fun reconcileMappings() {
        if (project.isDisposed) return
        discovery.refresh().thenAccept { snapshot ->
            val repositories = snapshot.roots.mapNotNull { it.repository }
                .distinctBy { "${it.serverProfileId}:${it.id}" }
            val desired = repositories.flatMap { repository ->
                listOf(
                    loopKey(repository, EventKind.PIPELINE) to repository,
                    loopKey(repository, EventKind.PULL_REQUEST) to repository,
                )
            }.toMap()
            synchronized(loops) {
                loops.keys.filter { it !in desired }.forEach { key -> loops.remove(key)?.close() }
                desired.forEach { (key, repository) ->
                    if (key !in loops) {
                        val kind = if (key.endsWith(":pipeline")) EventKind.PIPELINE else EventKind.PULL_REQUEST
                        SubscriptionLoop(repository, kind).also { loops[key] = it }.connect(0)
                    }
                }
            }
        }.whenComplete { _, error ->
            if (error != null && !project.isDisposed) LOG.warn("Unable to reconcile Bosca notification subscriptions", error)
        }
    }

    private inner class SubscriptionLoop(
        private val repository: BoscaRemoteRepository,
        private val kind: EventKind,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private var subscription: BoscaSubscription? = null
        private var retry: ScheduledFuture<*>? = null

        fun connect(attempt: Int) {
            if (closed.get() || project.isDisposed) return
            if (registry.profile(repository.serverProfileId) == null) return
            val failed = AtomicBoolean(false)
            fun reconnect() {
                if (!failed.compareAndSet(false, true) || closed.get()) return
                subscription?.close()
                val delays = longArrayOf(1, 2, 5, 15, 30, 60)
                val delay = delays[attempt.coerceIn(delays.indices)]
                retry = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    { connect((attempt + 1).coerceAtMost(delays.lastIndex)) },
                    delay,
                    TimeUnit.SECONDS,
                )
            }
            subscription = connections.subscribe(
                profileId = repository.serverProfileId,
                document = if (kind == EventKind.PIPELINE) PIPELINE_SUBSCRIPTION else PR_SUBSCRIPTION,
                variables = JsonObject().apply { addProperty("repositoryId", repository.id) },
                onNext = { data ->
                    if (kind == EventKind.PIPELINE) {
                        data.getAsJsonObject("gitPipelineEvents")?.let { onPipeline(repository, it) }
                    } else {
                        data.getAsJsonObject("gitPullRequestEvents")?.let { onPullRequest(repository, it) }
                    }
                },
                onError = { reconnect() },
                onComplete = { reconnect() },
            )
            resynchronize(repository, kind, notifyChanges = attempt > 0)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            retry?.cancel(false)
            subscription?.close()
        }
    }

    private fun resynchronize(repository: BoscaRemoteRepository, kind: EventKind, notifyChanges: Boolean) {
        connections.execute(
            repository.serverProfileId,
            if (kind == EventKind.PIPELINE) PIPELINE_SYNC_QUERY else PR_SYNC_QUERY,
            JsonObject().apply { addProperty("repositoryId", repository.id) },
        ).thenAccept { data ->
            val git = data.getAsJsonObject("git")
            if (kind == EventKind.PIPELINE) {
                git.getAsJsonArray("pipelineRuns").forEach { value ->
                    val run = value.asJsonObject
                    val id = run.get("id").asString
                    val status = run.get("status").asString
                    val stateKey = stateKey(repository, "pipeline", id)
                    val previous = settings.notificationState(stateKey)
                    settings.putNotificationState(stateKey, status)
                    if (notifyChanges && previous != null && previous != status) {
                        notifyPipeline(repository, id, status, "sync:$stateKey:$status")
                    }
                }
            } else {
                git.getAsJsonArray("pullRequests").forEach { value ->
                    val pr = value.asJsonObject
                    val id = pr.get("id").asString
                    val fingerprint = "${pr.get("status").asString}:${pr.get("updated").asString}"
                    val stateKey = stateKey(repository, "pr", id)
                    val previous = settings.notificationState(stateKey)
                    settings.putNotificationState(stateKey, fingerprint)
                    if (notifyChanges && previous != null && previous != fingerprint) {
                        notifyPullRequest(repository, pr.get("number").asInt, pr.get("title").asString, "UPDATED", null, null, "sync:$stateKey:$fingerprint")
                    }
                }
            }
        }.whenComplete { _, error ->
            if (error != null && !project.isDisposed) {
                LOG.warn("Unable to resynchronize ${kind.key} notification state for ${repository.id}", error)
            }
        }
    }

    private fun onPipeline(repository: BoscaRemoteRepository, event: JsonObject) {
        val runId = event.get("pipelineRunId").asString
        val status = event.get("status").asString
        settings.putNotificationState(stateKey(repository, "pipeline", runId), status)
        if (status !in setOf("RUNNING", "SUCCESS", "FAILURE", "CANCELLED")) return
        notifyPipeline(repository, runId, status, "${repository.serverProfileId}:${repository.id}:$runId:$status")
    }

    private fun notifyPipeline(repository: BoscaRemoteRepository, runId: String, status: String, fingerprint: String) {
        if (!deduplicator.firstOccurrence(fingerprint)) return
        val serverName = registry.profile(repository.serverProfileId)?.name ?: repository.serverProfileId
        val title = when (status) {
            "RUNNING" -> "Pipeline started"
            "SUCCESS" -> "Pipeline succeeded"
            "FAILURE" -> "Pipeline failed"
            "CANCELLED" -> "Pipeline cancelled"
            else -> "Pipeline updated"
        }
        val type = if (status == "FAILURE") NotificationType.ERROR else NotificationType.INFORMATION
        notification(title, "$serverName · ${repository.name} · run $runId", type, "Open pipeline") {
            navigate("Pipelines", BoscaNavigationTarget.Pipeline(repository.serverProfileId, repository.id, runId))
        }
    }

    private fun onPullRequest(repository: BoscaRemoteRepository, event: JsonObject) {
        val id = event.get("pullRequestId").asString
        val number = event.get("number").asInt
        val action = event.get("action").asString
        val title = event.get("title").asString
        val filePath = event.nullableString("filePath")
        val line = event.nullableInt("lineNumber")
        val fingerprint = listOf(
            repository.serverProfileId, repository.id, id, action,
            event.nullableString("actorId"), event.nullableString("body"), filePath, line,
            event.nullableString("reviewStatus"), event.nullableString("assigneeId"),
        ).joinToString(":")
        settings.putNotificationState(stateKey(repository, "pr", id), "$action:${System.currentTimeMillis()}")
        notifyPullRequest(repository, number, title, action, filePath, line, fingerprint)
    }

    private fun notifyPullRequest(
        repository: BoscaRemoteRepository,
        number: Int,
        title: String,
        action: String,
        filePath: String?,
        line: Int?,
        fingerprint: String,
    ) {
        if (!deduplicator.firstOccurrence(fingerprint)) return
        val serverName = registry.profile(repository.serverProfileId)?.name ?: repository.serverProfileId
        val actionText = action.lowercase().replace('_', ' ')
        val content = buildString {
            append(serverName).append(" · ").append(repository.name).append(" · #").append(number)
            append(" ").append(StringUtil.escapeXmlEntities(title)).append(" · ").append(actionText)
            if (filePath != null) append(" · ").append(filePath).append(line?.let { ":$it" }.orEmpty())
        }
        notification("Pull request $actionText", content, NotificationType.INFORMATION, "Open pull request") {
            navigate("Pull Requests", BoscaNavigationTarget.PullRequest(repository.serverProfileId, repository.id, number, filePath, line))
        }
    }

    private fun notification(title: String, content: String, type: NotificationType, actionTitle: String, action: () -> Unit) {
        NotificationGroupManager.getInstance().getNotificationGroup("Bosca Connections")
            .createNotification(title, content, type)
            .addAction(NotificationAction.createSimpleExpiring(actionTitle, action))
            .notify(project)
    }

    private fun navigate(tabName: String, target: BoscaNavigationTarget) {
        ApplicationManager.getApplication().invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Bosca") ?: return@invokeLater
            toolWindow.activate {
                toolWindow.contentManager.contents.firstOrNull { it.displayName == tabName }
                    ?.let { toolWindow.contentManager.setSelectedContent(it) }
                project.messageBus.syncPublisher(BoscaNavigationListener.TOPIC).navigate(target)
            }
        }
    }

    private fun stateKey(repository: BoscaRemoteRepository, type: String, entityId: String) =
        "${repository.serverProfileId}:${repository.id}:$type:$entityId"

    private fun loopKey(repository: BoscaRemoteRepository, kind: EventKind) =
        "${repository.serverProfileId}:${repository.id}:${kind.key}"

    override fun dispose() {
        reconciliation?.cancel(false)
        synchronized(loops) {
            loops.values.forEach(SubscriptionLoop::close)
            loops.clear()
        }
    }

    private fun JsonObject.nullableString(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.nullableInt(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }?.asInt

    private enum class EventKind(val key: String) { PIPELINE("pipeline"), PULL_REQUEST("pr") }

    companion object {
        private val LOG = Logger.getInstance(BoscaNotificationService::class.java)

        private const val PIPELINE_SUBSCRIPTION = """
            subscription BoscaIdePipelineEvents(${ '$' }repositoryId: UUID!) {
              gitPipelineEvents(repositoryId: ${ '$' }repositoryId) { repositoryId pipelineRunId pipelineId status }
            }
        """
        private const val PR_SUBSCRIPTION = """
            subscription BoscaIdePullRequestEvents(${ '$' }repositoryId: UUID!) {
              gitPullRequestEvents(repositoryId: ${ '$' }repositoryId) {
                repositoryId pullRequestId number action title actorId actorName body filePath lineNumber reviewStatus assigneeId
              }
            }
        """
        private const val PIPELINE_SYNC_QUERY = "query BoscaIdePipelineNotificationSync(${'$'}repositoryId: UUID!) { git { pipelineRuns(repositoryId: ${'$'}repositoryId, offset: 0, limit: 100) { id status } } }"
        private const val PR_SYNC_QUERY = "query BoscaIdePrNotificationSync(${'$'}repositoryId: UUID!) { git { pullRequests(repositoryId: ${'$'}repositoryId, offset: 0, limit: 100) { id number title status updated } } }"
    }
}

/** Starts notifications at project open; the Bosca tool window does not need to be opened first. */
class BoscaNotificationStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(BoscaNotificationService::class.java).start()
    }
}
