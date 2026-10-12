<script setup lang="ts">
definePageMeta({ layout: 'developers' })

useSeoMeta({
  title: 'Bosca Git',
  description: 'Host repositories, manage pull requests, and connect versioned source files to scripts and analytics queries.'
})
</script>

<template>
  <div class="doc-content article">
    <h1>Bosca Git</h1>
    <p class="subtitle">
      Host repositories, manage pull requests, and connect versioned source files to scripts and analytics queries.
    </p>

    <h2 id="overview">
      Overview
    </h2>
    <p>
      Bosca Git serves the <strong>Git smart HTTP protocol</strong> using <strong>JGit</strong>.
      Pack files live in S3-compatible object storage; refs and pack metadata live in PostgreSQL.
      The dedicated Git server handles clone, fetch, and push requests, while the platform API exposes
      repository management through GraphQL.
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
      Private repositories require authentication and repository permissions. Scoped API tokens use
      <code>git:read</code> for reads and <code>git:write</code> for writes.
      For a signed-in CLI account, <code>bosca git clone</code> provisions a Git credential and configures
      the Bosca credential helper:
    </p>

    <CodeBlock
      lang="bash"
      :code="`bosca --profile local git clone http://bosca.localhost:8091/OWNER/REPOSITORY.git`"
    />
    <p>
      Create the repository in Studio first and use its displayed clone URL in place of the example.
      The CLI requires Git on your PATH. Later plain <code>git push</code> and <code>git fetch</code>
      operations use the configured credential helper.
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
      :code="`mutation LinkScript($scriptId: UUID!, $repositoryId: UUID!, $path: String!, $ref: String!) {
  git {
    setScriptSourceRef(scriptId: $scriptId, repositoryId: $repositoryId, path: $path, ref: $ref) {
      scriptId repositoryId path ref
    }
  }
}`"
    />
    <p>
      Supply an existing script ID and repository ID, a file path such as
      <code>scripts/example/source.bosca.kts</code>, and a full ref such as
      <code>refs/heads/main</code>. With the runner's Git listeners enabled, pushes enqueue source-sync
      jobs. They find linked files changed on the selected ref, read the blobs from Git, and update
      the stored script source and version.
    </p>
    <p>The same flow works for <strong>analytics queries</strong> via <code>setQuerySourceRef</code> — SQL files stored in Git are synced to the analytics query store on push.</p>

    <Callout type="tip">
      This means you can version-control your scripts and queries in Git, review changes via pull requests, and update linked platform source when the selected ref changes. Keep the runner and Git listeners running for synchronization.
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
      <li><strong>Step-level logs</strong> with streaming via the CLI (<code>bosca ci run logs --step-id STEP_UUID --run-id RUN_UUID --follow</code>)</li>
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
    <p>Repository data is split between two backends:</p>
    <ul>
      <li><strong>Object data (packs, LFS objects)</strong> — streamed to and from S3-compatible object storage.</li>
      <li><strong>Refs and pack metadata</strong> — kept in PostgreSQL so any server pod can resolve refs without local disk.</li>
    </ul>
    <p>
      Git server instances use the shared database and object storage rather than a local repository
      directory. Keep those backing services available and use matching authentication and storage
      configuration across instances.
    </p>

    <h2 id="graphql">
      GraphQL API
    </h2>
    <p>The full Git API is available via GraphQL:</p>
    <CodeBlock
      lang="graphql"
      :code="`query RepositoryDetails($repositoryId: UUID!, $ref: String!) {
  git {
    repositories { id slug visibility defaultBranch }
    tree(repositoryId: $repositoryId, ref: $ref, path: &quot;&quot;) {
      name type size
    }
    commits(repositoryId: $repositoryId, ref: $ref, limit: 10) {
      sha message authorName authorEmail authorDate
    }
    pullRequests(repositoryId: $repositoryId, status: OPEN, offset: 0, limit: 10) {
      number title sourceBranch targetBranch
    }
  }
}`"
    />
  </div>
</template>
