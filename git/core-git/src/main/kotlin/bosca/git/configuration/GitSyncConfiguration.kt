package bosca.git.configuration

import bosca.server.BoscaApplication

/**
 * Whether this composition root runs the git-sync pull listeners (config key
 * `git.sync.listeners`, default true). Domain modules with git-synced entities
 * (agents, pipelines, scripts, analytics queries) consult this in their
 * `BoscaApplicationModule.install` instead of hardcoding per-root choices.
 *
 * Exactly one root per deployment should listen — the `bosca.git.push`
 * subscription is a broadcast, so every listening copy re-pulls the same push.
 * In the standard deployment that root is bosca-runner; the dedicated
 * bosca-git-server hosts the git transport and publishes the push events, and
 * pull-time validation in the listening root is what keeps invalid content out
 * of the database.
 */
fun BoscaApplication.gitSyncListenersEnabled(): Boolean =
    environment.config.propertyOrNull("git.sync.listeners")?.getString()?.toBoolean() ?: true
