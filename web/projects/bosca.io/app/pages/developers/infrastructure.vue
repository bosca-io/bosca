<script setup lang="ts">
definePageMeta({ layout: 'developers' })

useSeoMeta({
  title: 'Infrastructure',
  description: 'Choose the local image stack or source-development dependencies, and configure backing services.'
})
</script>

<template>
  <div class="doc-content article">
    <h1>Infrastructure</h1>
    <p class="subtitle">
      Choose the local image stack or source-development dependencies, and configure backing services.
    </p>

    <h2 id="local-dev">
      Local Development (Docker Compose)
    </h2>
    <p>
      To run Bosca locally using published images, follow
      <NuxtLink to="/developers/run-locally">Run Bosca Locally</NuxtLink>.
      The root <code>docker-compose.yaml</code> starts the applications and their backing services using published images. It keeps data in its own named volumes.
    </p>
    <p>
      When running the backend from source, start the development dependencies with
      <code>server/services/docker-compose.yaml</code>. Run these commands from the repository root:
    </p>
    <CodeBlock
      lang="bash"
      :code="`docker compose -f server/services/docker-compose.yaml up -d
docker compose -f server/services/docker-compose.yaml down`"
    />

    <h3>Services</h3>
    <table>
      <thead>
        <tr>
          <th>Service</th>
          <th>Image</th>
          <th>Port</th>
          <th>Purpose</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><strong>PostgreSQL</strong></td>
          <td><code>pgvector/pgvector:pg18</code></td>
          <td>5433</td>
          <td>Primary database</td>
        </tr>
        <tr>
          <td><strong>PostgreSQL (Warehouse)</strong></td>
          <td><code>postgres:18.1-alpine</code></td>
          <td>5434</td>
          <td>Iceberg catalog database</td>
        </tr>
        <tr>
          <td><strong>NATS</strong></td>
          <td><code>nats</code></td>
          <td>4222</td>
          <td>Messaging, pub/sub, job queues</td>
        </tr>
        <tr>
          <td><strong>Redis-compatible</strong></td>
          <td><code>docker.dragonflydb.io/dragonflydb/dragonfly</code></td>
          <td>6380</td>
          <td>Cache, distributed locks</td>
        </tr>
        <tr>
          <td><strong>Meilisearch</strong></td>
          <td><code>getmeili/meilisearch:v1.39.0</code></td>
          <td>7701</td>
          <td>Full-text search</td>
        </tr>
        <tr>
          <td><strong>S3Proxy</strong></td>
          <td><code>andrewgaul/s3proxy:3.0.0</code></td>
          <td>8000</td>
          <td>S3-compatible local object storage</td>
        </tr>
        <tr>
          <td><strong>Trino</strong></td>
          <td><code>trinodb/trino:479</code></td>
          <td>8089</td>
          <td>Distributed SQL / Iceberg analytics</td>
        </tr>
        <tr>
          <td><strong>Jaeger</strong></td>
          <td><code>cr.jaegertracing.io/jaegertracing/jaeger:2.13.0</code></td>
          <td>16686</td>
          <td>Distributed tracing (OTEL)</td>
        </tr>
        <tr>
          <td><strong>TensorFlow Serving</strong></td>
          <td><code>tensorflow/serving</code></td>
          <td>8501</td>
          <td>Serve recommendation models when model artifacts are present</td>
        </tr>
        <tr>
          <td><strong>Text Embeddings Inference</strong></td>
          <td>CPU embedding service</td>
          <td>8092</td>
          <td>Semantic embeddings for content indexing</td>
        </tr>
      </tbody>
    </table>

    <p>Start the server and runner in separate terminals from the repository root. JVM modules use the Java 25 toolchain:</p>
    <CodeBlock
      lang="bash"
      :code="`./gradlew :server:bosca-server:run     # GraphQL API on :8080
./gradlew :server:bosca-runner:run     # Background job processor`"
    />

    <p>
      For Studio development, run <code>pnpm install</code> in <code>web/</code>, then
      <code>pnpm --filter @bosca/studio dev</code>. Its development proxy targets the API on port 8080.
      The dependencies Compose file also offers a Git server through the <code>git-server</code>
      profile; alternatively run <code>./gradlew :git:git-server:run</code>.
    </p>
    <p>
      The dependency stack differs from the root image stack: it publishes backing-service ports
      for applications running on the host and includes embedding and model-serving services.
      It does not start Studio, the API, and the runner for you.
    </p>
    <h2 id="downstream">
      Downstream Systems
    </h2>

    <h3 id="required">
      Core backing services
    </h3>
    <table>
      <thead>
        <tr>
          <th>System</th>
          <th>Role</th>
          <th>Config</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><strong>PostgreSQL</strong></td>
          <td>Primary database — all models, permissions, workflow state</td>
          <td><code>DATABASE_URL</code>, <code>DATABASE_USER</code>, <code>DATABASE_PASSWORD</code></td>
        </tr>
        <tr>
          <td><strong>Redis-compatible</strong></td>
          <td>Cache, pub/sub, locks, and jobs when Redis backends are selected</td>
          <td><code>REDIS_HOST</code>, <code>REDIS_PORT</code></td>
        </tr>
        <tr>
          <td><strong>NATS</strong></td>
          <td>Default cache, pub/sub, durable jobs, and distributed locks</td>
          <td><code>NATS_HOST</code>, <code>NATS_TOKEN</code></td>
        </tr>
        <tr>
          <td><strong>S3 / GCS</strong></td>
          <td>Object storage for content files, Git pack files, LFS objects</td>
          <td><code>STORAGE_TYPE</code>, <code>STORAGE_BUCKET</code>, <code>STORAGE_ENDPOINT</code>, <code>STORAGE_ACCESS_KEY_ID</code>, <code>STORAGE_ACCESS_KEY_SECRET</code></td>
        </tr>
        <tr>
          <td><strong>Meilisearch</strong></td>
          <td>Full-text search for content and code</td>
          <td><code>MEILISEARCH_URL</code>, <code>MEILISEARCH_API_KEY</code></td>
        </tr>
      </tbody>
    </table>

    <h3 id="optional">
      Optional / Analytics
    </h3>
    <table>
      <thead>
        <tr>
          <th>System</th>
          <th>Role</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><strong>Trino</strong></td>
          <td>Distributed SQL for analytics queries over Iceberg/warehouse data</td>
        </tr>
        <tr>
          <td><strong>Jaeger</strong></td>
          <td>Distributed tracing via OpenTelemetry (OTLP on 4317/4318)</td>
        </tr>
        <tr>
          <td><strong>Text Extractor</strong></td>
          <td>Extracts text from PDFs, DOCX, and other document formats</td>
        </tr>
      </tbody>
    </table>

    <p>
      External integrations such as mail, OAuth2, and AI model providers need their own configuration
      and credentials. See <NuxtLink to="/developers/run-locally#integrations">Optional Integrations</NuxtLink>
      for the local image stack.
    </p>
    <h2 id="configurable">
      Configurable Backends
    </h2>
    <p>Several infrastructure services are swappable at startup via environment variables:</p>
    <table>
      <thead>
        <tr>
          <th>Concern</th>
          <th>Env Var</th>
          <th>Options</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td>Cache</td>
          <td><code>CACHE_TYPE</code></td>
          <td><code>nats</code> (default), <code>redis</code></td>
        </tr>
        <tr>
          <td>Pub/Sub</td>
          <td><code>PUBSUB_TYPE</code></td>
          <td><code>nats</code> (default), <code>redis</code></td>
        </tr>
        <tr>
          <td>Job Queue</td>
          <td><code>JOB_QUEUE_FACTORY</code></td>
          <td><code>nats</code> (default), <code>redis</code></td>
        </tr>
        <tr>
          <td>Distributed Locks</td>
          <td><code>DISTRIBUTED_LOCK_TYPE</code></td>
          <td><code>nats</code> (default), <code>redis</code></td>
        </tr>
        <tr>
          <td>Object Storage</td>
          <td><code>STORAGE_TYPE</code></td>
          <td><code>s3</code> (default), <code>gcs</code></td>
        </tr>
      </tbody>
    </table>

    <h2 id="kubernetes">
      Kubernetes Deployment
    </h2>
    <p>
      Bosca deploys to Kubernetes via the official Helm charts. See
      <NuxtLink to="/discover/kubernetes/helm">Helm</NuxtLink> for the chart catalog, install and upgrade
      flows, and values reference.
    </p>

    <h2 id="env-vars">
      Key Environment Variables
    </h2>
    <p>
      Configuration is loaded from <code>application.yaml</code> with <code>$VAR:default</code>
      substitution. The names below are read by the server and runner. This is a variable reference, not a complete environment file. Their checked-in local defaults match the dependency stack; the root Compose stack supplies container-network settings:
    </p>
    <CodeBlock
      lang="bash"
      title="Essential variables"
      :code="`BOSCA_SERVER_PORT=8080
DATABASE_URL=jdbc:postgresql://localhost:5433/bosca
DATABASE_USER=bosca
DATABASE_PASSWORD=bosca
REDIS_HOST=localhost
REDIS_PORT=6380
NATS_HOST=localhost:4222
NATS_TOKEN=...
STORAGE_TYPE=s3
STORAGE_ENDPOINT=http://localhost:8000
STORAGE_BUCKET=storage
STORAGE_ACCESS_KEY_ID=bosca-s3
STORAGE_ACCESS_KEY_SECRET=bosca-s3
MEILISEARCH_URL=http://localhost:7701
MEILISEARCH_API_KEY=...
JWT_SECRET=your-secret-here
CACHE_TYPE=nats
PUBSUB_TYPE=nats
JOB_QUEUE_FACTORY=nats
DISTRIBUTED_LOCK_TYPE=nats`"
    />
  </div>
</template>
