# Bosca for IntelliJ IDEA

The Bosca plugin brings Bosca development workflows into IntelliJ IDEA. It connects one project to repositories and WorkOps projects on any number of Bosca servers, then exposes pipelines, issues, specifications, pull requests, reviews, and native code diffs in the **Bosca** tool window.

## Features

- Multiple named Bosca servers bound to Bosca CLI profiles. The plugin uses the same API-token and refreshable-session lifecycle as `bosca login`.
- Per-project server selection and per-Git-root repository mappings. Exact clone URL matches are mapped automatically; ambiguous roots can be mapped manually.
- Pipeline trees with run, job, and step status; historical and live logs; manual runs; cancellation; reruns; failed-job reruns; and approval controls.
- WorkOps issue and specification browsing across servers and projects. Create or edit issues, assignments, comments, links, transitions, resolutions, specifications, requirements, and their Markdown-backed documents.
- Pull-request dashboards with status filters, metadata, assignees, dependencies, conflicts, checks, associated pipeline runs, reviews, threads, and changed files.
- Pull-request management for editing, assigning, marking ready, closing, reopening, merging, submitting review verdicts, replying to or resolving threads, and adding exact old/new-line comments.
- Native IntelliJ two-sided diffs with syntax highlighting, standard diff navigation, and visible current review anchors. Deleted and renamed files retain the correct old/new-side anchors; outdated anchors are kept in review history without being attached to current source lines.
- Actionable IDE notifications when mapped-repository pipelines start or finish and when pull requests are opened, updated, reviewed, commented on, assigned, merged, or otherwise changed. Selecting a notification opens the relevant server, repository, run, pull request, file, and line when available.

HTTP operations and subscriptions use the shared `io.bosca:bosca-graphql-client` runtime. Subscriptions reconnect with bounded backoff. After reconnecting, the plugin compares the server state with its persisted project-local fingerprints so status changes that happened while IntelliJ was disconnected can still be surfaced.

## Build and verify

Run the plugin tasks from the workspace root:

```bash
./gradlew :workops:workops-ide:test
./gradlew :workops:workops-ide:buildPlugin
./gradlew :workops:workops-ide:verifyPlugin
```

The installable archive is written to `workops/workops-ide/build/distributions/`.

## Configure servers

1. Authenticate with the CLI, for example `bosca login --profile work --url https://example.com/graphql --oauth2 google`.
2. Install the generated archive through **Settings → Plugins → Install Plugin from Disk**.
3. Open **Settings → Bosca Servers** and add a server by selecting the corresponding Bosca CLI profile. Its GraphQL endpoint comes from the CLI profile; the WebSocket endpoint defaults from it and can be overridden.
4. Use **Test Connection** before applying the settings.

Only non-secret server metadata and the selected CLI profile name are stored in IntelliJ application settings. Credentials remain in the CLI's XDG-aware `config.json` (`~/.config/bosca/config.json` by default). The plugin reads API tokens as-is, lazily refreshes JWT sessions through the shared Bosca authentication library, and writes rotated credentials back with the CLI's lock-and-atomic-update contract. Run `bosca login` or `bosca logout` to manage credentials; the plugin never asks for or stores a token in IntelliJ Password Safe. Password Safe credentials saved by an earlier plugin version are ignored.

## Map a project

Open the **Bosca** tool window, choose **Servers for Project…**, and refresh. Clone URLs are compared across all enabled Bosca servers. An exact, unique match is mapped automatically; ambiguous or unmatched Git roots can be assigned with **Map Repository…**. Project state stores only server-profile and repository identifiers.

The **Pipelines** and **Pull Requests** tabs follow repository mappings. The **WorkOps** tab lets you independently choose a Bosca server and WorkOps project, which is useful when a workspace spans repositories or planning projects on different servers.

## Notifications

Notifications start when the IntelliJ project opens; the Bosca tool window does not need to be open. They are scoped to repositories mapped in the current project and honor the current user's repository view permission on each Bosca server. Use IntelliJ's notification settings for the **Bosca Connections** group to adjust presentation or disable them.

The backend must expose the repository-scoped `gitPipelineEvents` and `gitPullRequestEvents` GraphQL subscriptions included with this change. Historical synchronization still works over GraphQL HTTP, while live delivery uses `graphql-transport-ws`.
