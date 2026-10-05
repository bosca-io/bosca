<script setup lang="ts">
useSeoMeta({ title: 'Infrastructure' })
</script>

<template>
  <div class="doc-content article">
    <h1>Infrastructure</h1>
    <p class="subtitle">
      Running Bosca locally with Docker Compose, deploying to Kubernetes with the CLI, and understanding the downstream systems.
    </p>

    <h2 id="local-dev">
      Local Development (Docker Compose)
    </h2>
    <p>
      The full dependency stack runs via Docker Compose. The root
      <code>docker-compose.yaml</code> includes <code>server/services/docker-compose.yaml</code>,
      which defines every backing service.
    </p>
    <CodeBlock
      lang="bash"
      :code="`docker compose up -d      # Start all services
docker compose down       # Stop all services`"
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
          <td><code>postgres:18.1-alpine</code></td>
          <td>5433</td>
          <td>Primary database</td>
        </tr>
        <tr>
          <td><strong>PostgreSQL (Warehouse)</strong></td>
          <td><code>postgres:18.1-alpine</code></td>
          <td>5434</td>
          <td>Analytics warehouse</td>
        </tr>
        <tr>
          <td><strong>NATS</strong></td>
          <td><code>nats</code></td>
          <td>4222</td>
          <td>Messaging, pub/sub, job queues</td>
        </tr>
        <tr>
          <td><strong>Redis-compatible</strong></td>
          <td><code>dragonflydb/dragonfly</code></td>
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
          <td><code>jaegertracing/jaeger:2.13.0</code></td>
          <td>16686</td>
          <td>Distributed tracing (OTEL)</td>
        </tr>
        <tr>
          <td><strong>Text Extractor</strong></td>
          <td>Text extraction service</td>
          <td>8083</td>
          <td>Document text extraction (PDF, DOCX, etc.)</td>
        </tr>
      </tbody>
    </table>

    <p>Then start the server and runner:</p>
    <CodeBlock
      lang="bash"
      :code="`./gradlew :bosca-server:run     # GraphQL API on :8080
./gradlew :bosca-runner:run     # Background job processor`"
    />

    <h2 id="downstream">
      Downstream Systems
    </h2>

    <h3 id="required">
      Required
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
          <td>Cache, distributed locks, job queues (when configured)</td>
          <td><code>REDIS_HOST</code>, <code>REDIS_PORT</code></td>
        </tr>
        <tr>
          <td><strong>NATS</strong></td>
          <td>Messaging, pub/sub, job queues, distributed locks, cache (when configured)</td>
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

    <h3 id="integrations">
      Integrations
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
          <td><strong>Mux</strong></td>
          <td>Video upload, transcoding, and streaming</td>
        </tr>
        <tr>
          <td><strong>HubSpot</strong></td>
          <td>CRM contact sync</td>
        </tr>
        <tr>
          <td><strong>SendGrid</strong></td>
          <td>Transactional email</td>
        </tr>
        <tr>
          <td><strong>Crowdin</strong></td>
          <td>Localization / translation management</td>
        </tr>
        <tr>
          <td><strong>Google OAuth2 / TTS / GenAI</strong></td>
          <td>Authentication, text-to-speech, AI features</td>
        </tr>
        <tr>
          <td><strong>OpenAI / Anthropic</strong></td>
          <td>AI model providers for chat, agents, and tools</td>
        </tr>
        <tr>
          <td><strong>PostHog</strong></td>
          <td>Product analytics and experimentation</td>
        </tr>
      </tbody>
    </table>

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
          <td><code>redis</code> (default), <code>nats</code></td>
        </tr>
        <tr>
          <td>Pub/Sub</td>
          <td><code>PUBSUB_TYPE</code></td>
          <td><code>nats</code> (default), <code>redis</code></td>
        </tr>
        <tr>
          <td>Job Queue</td>
          <td><code>JOB_QUEUE_FACTORY</code></td>
          <td><code>redis</code> (default), <code>nats</code></td>
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
      substitution. The names below are read directly by the server and runner:
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
CACHE_TYPE=redis
PUBSUB_TYPE=nats
JOB_QUEUE_FACTORY=redis
DISTRIBUTED_LOCK_TYPE=nats`"
    />
  </div>
</template>
