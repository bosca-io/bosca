package bosca.pipelines.configuration

import bosca.di.provide
import bosca.git.configuration.gitSyncListenersEnabled
import bosca.pipelines.git.PipelineGitPushListener
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule

/**
 * Git-sync wiring for the pipelines stack: when `git.sync.listeners` is enabled,
 * starts the push listener that pulls PIPELINE_PROJECT repositories back into the
 * DB. Enable on exactly one root per deployment (bosca-runner) — the PubSub
 * subscription is a broadcast, so every listening copy re-pulls the same push.
 *
 * Pushes land on the dedicated bosca-git-server (which loads no domain modules),
 * so pipeline content is validated at pull time: an invalid tree never upserts.
 */
class PipelinesModule : BoscaApplicationModule {

    override suspend fun install(application: BoscaApplication) {
        if (application.gitSyncListenersEnabled()) {
            PipelineGitPushListener(
                pubSubService = provide(),
                pipelineGitSyncService = provide(),
            )
        }
    }
}
