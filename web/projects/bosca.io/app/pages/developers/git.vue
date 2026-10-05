<script setup lang="ts">
useSeoMeta({ title: 'Bosca Git' })
</script>

<template>
  <div class="doc-content article">
    <h1>Bosca Git</h1>
    <p class="subtitle">
      A full, self-hosted Git server with Object Storage, PostgreSQL refs, pull requests, branch protection, CI/CD pipelines — and the source of truth for scripts and analytics queries.
    </p>

    <h2 id="overview">
      Overview
    </h2>
    <p>
      Bosca Git is a <strong>complete, self-hosted Git server</strong> — not a wrapper around any external hosting service. It implements the <strong>Git smart HTTP protocol</strong> natively using <strong>JGit</strong>, stores pack files in <strong>S3-compatible object storage</strong>, and keeps ref metadata in <strong>PostgreSQL</strong>. This makes the server completely stateless — multiple pods can serve the same repositories concurrently.
    </p>

    <h2 id="protocol">
      Git Protocol
    </h2>
    <p>Standard Git operations work out of the box:</p>
    <CodeBlock
      lang="bash"
      :code="`git clone https://git.example.com/my-org/my-repo.git
git push origin main
git fetch --all`"
    />
    <p>
      All I/O is <strong>fully streamed</strong> — pack files are piped directly between Netty and JGit without memory buffering. Authentication uses bearer tokens; scoped API tokens require <code>git:read</code> / <code>git:write</code> scopes.
    </p>

    <h2 id="content-types">
      Repository Content Types
    </h2>
    <p>Repositories are classified by content type, which enables specialized post-push behaviors:</p>
    <table>
      <thead>
        <tr>
          <th>Content Type</th>
          <th>Purpose</th>
          <th>On Push</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>GENERAL</code></td>
          <td>Any Git content</td>
          <td>Index + webhooks</td>
        </tr>
        <tr>
          <td><code>SCRIPT_PROJECT</code></td>
          <td>Script source files</td>
          <td>Detects changed scripts, syncs source to scripting engine</td>
        </tr>
        <tr>
          <td><code>DOCUMENTATION</code></td>
          <td>Documentation content</td>
          <td>Index + webhooks</td>
        </tr>
        <tr>
          <td><code>ANALYTIC_QUERY_PROJECT</code></td>
          <td>Analytic query definitions</td>
          <td>Detects changed queries, syncs to analytics engine</td>
        </tr>
      </tbody>
    </table>

    <h2 id="source-refs">
      Source Refs — Scripts &amp; Queries from Git
    </h2>
    <p>
      Git serves as the <strong>source of truth</strong> for scripts and analytics queries. A <strong>source ref</strong> links a script or query to a specific file in a Git repository:
    </p>
    <CodeBlock
      lang="graphql"
      title="Link a script to a Git file"
      :code="`mutation {
    git {
        setScriptSourceRef(
            scriptId: &quot;script-uuid&quot;
            repositoryId: &quot;repo-uuid&quot;
            path: &quot;scripts/my-script/source.bosca.kts&quot;
            ref: &quot;refs/heads/main&quot;
        )
    }
}`"
    />
    <p>When a push lands on the linked branch and the file has changed:</p>
    <ol>
      <li>The <strong>ContentChangeWatcher</strong> job detects the changed file</li>
      <li>Reads the blob content at the new commit SHA via JGit DFS</li>
      <li>Updates the script source in the scripting engine's database</li>
      <li>Dispatches a <code>ScriptSourceUpdatedEvent</code> for cache invalidation</li>
    </ol>
    <p>The same flow works for <strong>analytics queries</strong> via <code>setQuerySourceRef</code> — SQL files stored in Git are synced to the analytics query store on push.</p>

    <Callout type="tip">
      This means you can version-control your scripts and queries in Git, review changes via pull requests, and have them automatically deployed to the platform on merge.
    </Callout>

    <h2 id="pull-requests">
      Pull Requests &amp; Reviews
    </h2>
    <p>Bosca Git has a full pull request system:</p>
    <ul>
      <li><strong>Create PRs</strong> — specify source/target branches, title, description, assignees</li>
      <li><strong>Reviews</strong> — approve, request changes, or comment with inline review comments on specific files/lines</li>
      <li><strong>Merge strategies</strong> — merge commit, squash, or rebase (configurable per-repository)</li>
      <li><strong>Auto-delete branches</strong> — configurable via repository settings</li>
    </ul>

    <h2 id="branch-protection">
      Branch Protection
    </h2>
    <p>Enforce rules on protected branches:</p>
    <ul>
      <li><strong>Block force pushes</strong></li>
      <li><strong>Block branch deletion</strong></li>
      <li><strong>Require pull requests</strong> — direct pushes are rejected; changes must go through a PR</li>
      <li><strong>Push access restrictions</strong> — limit who can push to the branch</li>
    </ul>

    <h2 id="hooks">
      Push Hooks
    </h2>

    <h3>Pre-receive</h3>
    <p>Enforces branch protection rules before refs are updated. Rejects pushes that violate protection policies.</p>

    <h3>Post-receive</h3>
    <p>After a successful push:</p>
    <ol>
      <li>Updates repository disk size</li>
      <li>Dispatches <strong>webhook events</strong> (push, branch created/deleted, tag created/deleted)</li>
      <li>Extracts <strong>WorkOps task keys</strong> from commit messages (pattern <code>[A-Z][A-Z0-9_]+-\d+</code>) and stores references</li>
      <li>Enqueues <strong>search index</strong> jobs (repository metadata + file content)</li>
      <li>Triggers <strong>CI/CD pipelines</strong> for updated branches</li>
      <li>Dispatches content change events for <code>SCRIPT_PROJECT</code> repos</li>
    </ol>

    <h2 id="ci-cd">
      CI/CD Pipelines
    </h2>
    <p>Bosca Git includes a built-in CI/CD system:</p>
    <ul>
      <li><strong>Pipeline definitions</strong> parsed from YAML files in the repository</li>
      <li><strong>Trigger types</strong>: push, pull request, tag, manual, schedule</li>
      <li><strong>Agent modes</strong>: runner (executes jobs) and orchestrator (manages ephemeral VMs)</li>
      <li><strong>Concurrency groups</strong> to prevent conflicting runs</li>
      <li><strong>Per-repository secrets</strong> (encrypted at rest)</li>
      <li><strong>Step-level logs</strong> with streaming via the CLI (<code>bosca ci run logs --follow</code>)</li>
    </ul>

    <h2 id="webhooks">
      Webhooks
    </h2>
    <p>
      Webhooks deliver push, PR, and pipeline events to external URLs with <strong>HMAC-SHA256</strong> signatures. Failed deliveries retry with exponential backoff (10s, 60s, 300s). Delivery history is queryable via GraphQL.
    </p>

    <h2 id="storage">
      Storage Architecture
    </h2>
    <p>The server is stateless — Git object bytes and ref metadata are split between two backends:</p>
    <ul>
      <li><strong>Object data (packs, LFS objects)</strong> — streamed to and from S3-compatible object storage.</li>
      <li><strong>Refs and pack metadata</strong> — kept in PostgreSQL so any server pod can resolve refs without local disk.</li>
    </ul>
    <p>
      This means clones, fetches, and pushes scale horizontally: multiple pods can serve the
      same repositories concurrently, and adding capacity is a matter of running more pods.
    </p>

    <h2 id="graphql">
      GraphQL API
    </h2>
    <p>The full Git API is available via GraphQL:</p>
    <CodeBlock
      lang="graphql"
      :code="`query {
    git {
        repositories { id slug visibility defaultBranch }
        tree(repositoryId: &quot;uuid&quot;, ref: &quot;main&quot;, path: &quot;/&quot;) {
            entries { name type size }
        }
        commits(repositoryId: &quot;uuid&quot;, ref: &quot;main&quot;, limit: 10) {
            sha message author { name email } date
        }
        pullRequests(repositoryId: &quot;uuid&quot;, state: OPEN) {
            number title sourceBranch targetBranch
        }
    }
}`"
    />
  </div>
</template>
