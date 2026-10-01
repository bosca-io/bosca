package bosca.ai.configuration

import bosca.ai.agents.git.AgentGitPushListener
import bosca.di.provide
import bosca.git.configuration.gitSyncListenersEnabled
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule

/**
 * Full AI wiring for composition roots that host the git-backed agent stack
 * (e.g. bosca-server, bosca-runner).
 *
 * Registers the Koog [bosca.ai.configuration.AILlmExecutorModule] (LLM
 * `PromptExecutor`) and, when `git.sync.listeners` is enabled, the push
 * listener that syncs agent repositories. Enable on exactly one root per
 * deployment (bosca-runner) — the subscription is a broadcast, so every
 * listening copy re-pulls the same push. Pushes land on the dedicated
 * bosca-git-server (which loads no domain modules), so agent content is
 * validated at pull time: an invalid tree never upserts.
 *
 * Composition roots that only need the LLM executor (and not the git stack)
 * should install [AILlmExecutorModule] directly instead of this module.
 */
class AIModule : BoscaApplicationModule {

    override suspend fun install(application: BoscaApplication) {
        AILlmExecutorModule().install(application)

        if (application.gitSyncListenersEnabled()) {
            AgentGitPushListener(
                pubSubService = provide(),
                agentGitSyncService = provide(),
            )
        }
    }
}
