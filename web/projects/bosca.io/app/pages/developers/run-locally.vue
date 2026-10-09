<script setup lang="ts">
definePageMeta({ layout: 'developers' })

useSeoMeta({
  title: 'Run Bosca Locally',
  description: 'Start Bosca locally with Docker Compose and sign in to Studio.'
})
</script>

<template>
  <div class="doc-content article">
    <h1>Run Bosca Locally</h1>
    <p class="subtitle">
      Start Bosca on your computer with Docker Compose to explore Studio, manage content,
      run jobs and pipelines, host Git repositories, and collect analytics.
    </p>
    <p>
      The root stack includes the Primary GraphQL Server, a Job Runner, Studio, Git and Artifacts servers,
      BML message rendering, image processing, PostgreSQL with pgvector, NATS, Redis,
      Meilisearch, S3 storage, and a Trino and Iceberg warehouse.
    </p>
    <p>
      This setup uses local HTTP and default credentials. Published ports bind to loopback.
      For deployment on a public host, configure your own ingress, HTTPS, credentials, and storage.
      Or use the `bosca swarm` commands.
    </p>

    <h2 id="requirements">
      Requirements
    </h2>
    <ul>
      <li>Docker Engine or Docker Desktop with Docker Compose v2.</li>
      <li>A checkout of the Bosca repository.</li>
      <li>Memory for the applications and databases; Trino alone uses a 2 GiB heap.</li>
      <li>Available local ports 3000, 8091, and 8084.</li>
    </ul>
    <p>
      The application images use <code>linux/amd64</code>. On an ARM machine, Docker Desktop
      needs amd64 emulation enabled.
    </p>

    <h2 id="start">
      Start Bosca
    </h2>
    <p>From the repository root:</p>
    <CodeBlock
      lang="bash"
      code="docker compose up"
    />
    <p>
      No configuration file or separate pull command is required. Compose downloads missing
      images and starts the services. Keep this terminal open while using Bosca; press
      <code>Ctrl+C</code> to stop the containers. To run in the background and wait for readiness:
    </p>
    <CodeBlock
      lang="bash"
      :code="`docker compose up -d --wait --wait-timeout 600
docker compose ps`"
    />
    <p>
      Compose waits for dependencies and application readiness. The one-time
      <code>storage-init</code> container creates the <code>storage</code> and
      <code>warehouse</code> buckets and exits successfully. The API applies database
      migrations and installs initial platform data. The runner processes background jobs
      and analytics events.
    </p>

    <h2 id="sign-in">
      Sign In to Studio
    </h2>
    <p>
      Open <a href="http://bosca.localhost:3000">http://bosca.localhost:3000</a> and sign in
      with username <code>admin</code> and password <code>password</code>.
      You can override the initial password with <code>INIT_ADMIN_PASSWORD</code>.
    </p>
    <p>
      The installer creates this account on the first start. Changing
      <code>INIT_ADMIN_PASSWORD</code> later does not reset an existing account's password.
    </p>
    <p>
      Use <code>bosca.localhost</code> consistently. Browsers resolve the name to loopback,
      while containers resolve it to the stack's web proxy. This lets image processing fetch
      the same signed content URLs used by the browser. If another client does not resolve
      it, add <code>127.0.0.1 bosca.localhost</code> to that machine's hosts file or use
      its explicit DNS override.
    </p>

    <h2 id="try">
      Try the Instance
    </h2>
    <p>
      Create a collection, upload a file, or use the editor to create a document in Studio.
      Git clone URLs point to the dedicated
      Git server. The Artifacts server is available for publishing and retrieving artifacts.
    </p>
    <table>
      <thead>
        <tr>
          <th>Surface</th>
          <th>Default URL</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td>Studio</td>
          <td><code>http://bosca.localhost:3000</code></td>
        </tr>
        <tr>
          <td>GraphQL API</td>
          <td><code>http://bosca.localhost:3000/graphql</code></td>
        </tr>
        <tr>
          <td>Git server</td>
          <td><code>http://bosca.localhost:8091</code></td>
        </tr>
        <tr>
          <td>Artifacts server</td>
          <td><code>http://bosca.localhost:8084</code></td>
        </tr>
      </tbody>
    </table>
    <p>
      Studio's API requests, GraphQL subscriptions, and collaborative editing use the same
      web origin. Databases, object storage, queues, search, image processing, and the message
      rendering API are internal to the Compose network.
    </p>

    <h2 id="integrations">
      Optional Configuration and Integrations
    </h2>
    <p>
      To override the defaults, copy <code>.env.example</code> to <code>.env</code> and edit the
      settings you need. <code>BOSCA_IMAGE_REGISTRY</code> selects a registry mirror, and
      <code>BOSCA_VERSION</code> selects the application tag.
      The image processor has a separate tag, <code>BOSCA_IMAGEPROCESSOR_VERSION</code>.
      The default tags are pinned in the root Compose file. Public images come from <code>ghcr.io/bosca-io</code>.
      Recreate the containers after changing settings.
    </p>
    <p>
      To refresh the repository's default Bosca image versions, run the following with <code>curl</code>
      and <code>jq</code> installed.
      The script selects the newest stable release available for all application images and updates
      the image processor separately. It checks the published manifests before editing the Compose
      file and <code>.env.example</code>. Existing <code>.env</code> overrides still take precedence.
      Add <code>--dry-run</code> to preview changes.
    </p>
    <CodeBlock
      lang="bash"
      :code="`scripts/update-compose-versions.sh
docker compose up -d`"
    />
    <p>
      Content storage, ordinary search, job processing, Git hosting, and the analytics
      warehouse use the services in this stack.
    </p>
    <ul>
      <li>
        <strong>AI:</strong> set <code>OPENAI_API_KEY</code> for the agent runtime.
        Configure providers for other model workflows through Studio.
      </li>
      <li>
        <strong>Google sign-in:</strong> set <code>GOOGLE_CLIENT_ID</code> and
        <code>GOOGLE_CLIENT_SECRET</code> with the callback
        <code>http://bosca.localhost:3000/oauth2/google/callback</code>.
      </li>
      <li>
        <strong>Email:</strong> use SendGrid or Mailgun. Set <code>MAILER_TYPE</code> and
        <code>MAILER_FROM_EMAIL</code>, then configure the provider's encrypted credentials
        in Studio.
      </li>
      <li>
        <strong>Message projects:</strong> the BML message server starts with the bundled
        <code>bosca-messages</code> project. To load projects published to this instance's
        Artifacts server, set <code>BML_MESSAGE_ARTIFACTS_TOKEN</code> to a token with read
        access to its <code>bml-message</code> repository.
      </li>
    </ul>
    <p>
      Semantic embeddings and trained recommendation models require their model services
      and artifacts. Kubernetes operations require a cluster and controller. Those services
      are not started by this Compose file.
    </p>

    <p>
      Continue with <NuxtLink to="/developers/cli">the CLI guide</NuxtLink> for programmatic access,
      or <NuxtLink to="/developers/graphql">GraphQL</NuxtLink> to send your first API request.
    </p>
    <h2 id="data">
      Keep or Reset Your Data
    </h2>
    <CodeBlock
      lang="bash"
      :code="`docker compose down
docker compose up -d --wait --wait-timeout 600`"
    />
    <p>
      Stopping the stack preserves named volumes containing PostgreSQL data, objects, NATS
      streams, Redis data, search indexes, message caches, and staged analytics. The project
      name is <code>bosca-local</code>, separate from the development stacks.
    </p>
    <p>To permanently delete this instance's data and start over:</p>
    <CodeBlock
      lang="bash"
      :code="`docker compose down --volumes
docker compose up -d --wait --wait-timeout 600`"
    />

    <h2 id="troubleshooting">
      Troubleshooting
    </h2>
    <p>Inspect the failing service before retrying:</p>
    <CodeBlock
      lang="bash"
      :code="`docker compose ps -a
docker compose logs --tail=100 bosca-server bosca-runner
docker compose logs --tail=100 SERVICE`"
    />
    <p>
      If pulling an image reports <code>manifest unknown</code>, verify that the selected
      release contains every image in the stack. If a port is in use, change
      <code>BOSCA_PORT</code>, <code>BOSCA_GIT_PORT</code>, or
      <code>BOSCA_ARTIFACTS_PORT</code> in <code>.env</code> and recreate the services with
      <code>docker compose up -d --wait --wait-timeout 600</code>. Public URLs derive from
      those settings.
    </p>
    <p>
      To run the backend from source, follow the
      <NuxtLink to="/developers/infrastructure#local-dev">local development instructions</NuxtLink>.
      The root Compose stack has its own databases and volumes.
    </p>
  </div>
</template>
