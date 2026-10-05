package bosca.ide.navigation

import com.intellij.util.messages.Topic

sealed interface BoscaNavigationTarget {
    val serverProfileId: String
    val repositoryId: String

    data class Pipeline(
        override val serverProfileId: String,
        override val repositoryId: String,
        val runId: String,
    ) : BoscaNavigationTarget

    data class PullRequest(
        override val serverProfileId: String,
        override val repositoryId: String,
        val number: Int,
        val filePath: String? = null,
        val lineNumber: Int? = null,
    ) : BoscaNavigationTarget
}

fun interface BoscaNavigationListener {
    fun navigate(target: BoscaNavigationTarget)

    companion object {
        val TOPIC: Topic<BoscaNavigationListener> = Topic.create("Bosca navigation", BoscaNavigationListener::class.java)
    }
}
