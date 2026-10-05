package bosca.scripting

import bosca.di.provide
import bosca.git.configuration.gitSyncListenersEnabled
import bosca.scripting.service.ScriptingGitPushListener
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule

class ScriptingModule : BoscaApplicationModule {

    override suspend fun install(application: BoscaApplication) {
        // Enable on exactly one root per deployment (`git.sync.listeners`) — the push-event
        // subscription is a broadcast, so every listening copy re-syncs the same push.
        if (application.gitSyncListenersEnabled()) {
            ScriptingGitPushListener(
                pubSubService = provide(),
            )
        }
    }
}
