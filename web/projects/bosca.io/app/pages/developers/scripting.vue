<script setup lang="ts">
useSeoMeta({ title: 'Scripting' })
</script>

<template>
  <div class="doc-content article">
    <h1>Scripting</h1>
    <p class="subtitle">
      Write Kotlin scripts that run inside the platform — triggered by events, exposed as API
      endpoints, or used as AI agent tools. Scripts run inside the server JVM with guardrails
      against common dangerous operations, but are not fully sandboxed.
    </p>

    <h2 id="overview">
      Overview
    </h2>
    <p>
      Bosca includes an embedded <strong>Kotlin Script (KTS)</strong> engine. Scripts are <code>.bosca.kts</code> files evaluated by Kotlin's embedded compiler. They have access to the full Bosca service layer (content, collections, metadata, search, etc.) with some guardrails against common dangerous operations — though scripts run with significant privilege and can still cause damage if misused.
    </p>

    <h2 id="script-types">
      Script Types
    </h2>
    <table>
      <thead>
        <tr>
          <th>Type</th>
          <th>Purpose</th>
          <th>Execution</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>GENERAL</code></td>
          <td>Utility scripts</td>
          <td>Manual via GraphQL</td>
        </tr>
        <tr>
          <td><code>TRIGGER</code></td>
          <td>React to platform events</td>
          <td>Async via job queue</td>
        </tr>
        <tr>
          <td><code>TOOL</code></td>
          <td>AI agent tools</td>
          <td>Called by AI agents</td>
        </tr>
        <tr>
          <td><code>API</code></td>
          <td>HTTP endpoints</td>
          <td><code>GET/POST /api/v1/s/{key}</code></td>
        </tr>
        <tr>
          <td><code>EPHEMERAL</code></td>
          <td>Temporary/one-shot scripts</td>
          <td>Auto-purged after retention period</td>
        </tr>
      </tbody>
    </table>

    <h2 id="writing-scripts">
      Writing Scripts
    </h2>
    <p>
      Scripts extend <code>BoscaScript</code> implicitly. They have access to a <code>context</code> object, the <code>authentication</code> context, a coroutine <code>scope</code>, and a <code>log</code> logger. Use the <code>main {}</code> block for async work:
    </p>
    <CodeBlock
      lang="kotlin"
      title="Example: trigger script"
      :code="`// Available imports are pre-configured:
// bosca.content.*, bosca.serialization.UUID, kotlinx.coroutines.*, etc.

val collectionService: CollectionService = provide()

main {
    val event = context.event  // TriggerContext provides the event payload
    log.info(&quot;Processing event: {}&quot;, event.eventName)

    val collections = collectionService.getAll(0, 100)
    for (collection in collections) {
        log.info(&quot;Collection: {} ({}), collection.name, collection.id)
    }
}`"
    />

    <CodeBlock
      lang="kotlin"
      title="Example: API script"
      :code="`// Accessible via GET/POST /api/v1/s/my-script-key
val metadataService: MetadataService = provide()

main {
    val input = context.input  // JSON body or query params as JsonElement
    val id = UUID.parse(input.jsonObject[&quot;id&quot;]!!.jsonPrimitive.content)
    val metadata = metadataService.getById(id)
    context.setOutput(metadata)  // Sets the response body
}`"
    />

    <Callout type="info">
      Scripts resolve services using <code>provide&lt;T&gt;()</code> — the same DI mechanism used by the rest of the platform. The full server classpath is available at compile time.
    </Callout>

    <h2 id="triggers">
      Running Scripts from Events
    </h2>
    <p>
      To run a script when a <strong>platform event</strong> fires, create a <strong>Pipeline</strong> whose accepted input type is the event, add an <strong>Execute Script</strong> node, and mark the pipeline <em>Active</em>. When the event fires, the pipeline is enqueued as a background job and executed asynchronously — and the same graph can filter, transform, and combine the event data before the script runs.
    </p>
    <p>Triggered pipelines execute as a dedicated <strong>service account</strong> (not the originating user), ensuring consistent permissions for automated processing.</p>

    <h3>Available events</h3>
    <p>Any event dispatched via the platform's event system can trigger pipelines. Common events include:</p>
    <ul>
      <li><code>bosca.content.collection.created</code> / <code>updated</code> / <code>state</code></li>
      <li><code>bosca.content.metadata.created</code> / <code>updated</code> / <code>state</code></li>
      <li><code>bosca.git.push</code></li>
      <li>Analytics pipeline events, form submissions, profile changes, etc.</li>
    </ul>

    <h2 id="api-scripts">
      API Scripts
    </h2>
    <p>
      Scripts with type <code>API</code> are exposed as HTTP endpoints at <code>/api/v1/s/{key}</code>:
    </p>
    <ul>
      <li><strong>POST</strong> — request body is parsed as JSON and available as <code>context.input</code></li>
      <li><strong>GET</strong> — query parameters are converted to a JSON object</li>
      <li><strong>Public scripts</strong> — no authentication required</li>
      <li><strong>Non-public scripts</strong> — caller must have <code>EXECUTE</code> permission</li>
      <li><strong>Timeout</strong> — 30 seconds</li>
    </ul>

    <h2 id="tool-scripts">
      AI Agent Tools
    </h2>
    <p>
      Scripts with type <code>TOOL</code> are automatically registered as <strong>AI agent tools</strong>. They include an <code>inputSchema</code> (JSON Schema) that the AI uses to understand what parameters to pass:
    </p>
    <CodeBlock
      lang="kotlin"
      title="Tool script with input schema"
      :code="`// inputSchema: { &quot;type&quot;: &quot;object&quot;, &quot;properties&quot;: { &quot;query&quot;: { &quot;type&quot;: &quot;string&quot; } } }
val searchService: SearchService = provide()

main {
    val query = context.input.jsonObject[&quot;query&quot;]!!.jsonPrimitive.content
    val results = searchService.search(query, 0, 10)
    context.setOutput(results)
}`"
    />

    <h2 id="security">
      Security Guardrails
    </h2>
    <p>Scripts have some protections against accidental misuse, but they are <strong>not a full sandbox</strong>. A script running without authentication can still call services and modify data. These guardrails reduce the surface area for common mistakes:</p>

    <h3>Source validation</h3>
    <p>Applied before saving and before compilation. Blocks:</p>
    <ul>
      <li>Import aliasing of <code>System</code> (e.g., <code>import java.lang.System as Sys</code>)</li>
      <li>Method references to <code>System</code></li>
      <li>Any <code>System.xxx</code> call except: <code>currentTimeMillis</code>, <code>nanoTime</code>, <code>lineSeparator</code>, <code>identityHashCode</code>, <code>arraycopy</code></li>
      <li>Any reference to <code>Runtime</code></li>
    </ul>

    <h3>Restricted class loader</h3>
    <p>Allowlist-based. Scripts can only load classes from:</p>
    <ul>
      <li><code>bosca.*</code>, <code>kotlin.*</code>, <code>kotlinx.*</code>, <code>java.lang.*</code>, <code>java.util.*</code>, <code>java.time.*</code>, <code>java.math.*</code>, <code>java.text.*</code>, <code>org.slf4j.Logger</code></li>
    </ul>
    <p>Hard-denied even within allowed prefixes: <code>ProcessBuilder</code>, <code>reflect.*</code>, <code>ClassLoader</code>, <code>java.io.*</code>, <code>java.nio.*</code>, <code>java.net.*</code>, <code>Runtime</code>, <code>Thread</code></p>

    <h3>Execution timeout</h3>
    <p>Default 600 seconds (configurable). API scripts have a 30-second timeout.</p>

    <h3>Permissions</h3>
    <p>Scripts have group-based ACL via <code>ScriptPermission</code> (VIEW/EXECUTE/MANAGE). Managing scripts requires the admin group.</p>

    <h2 id="compilation">
      Compilation &amp; Caching
    </h2>
    <p>Scripts are compiled to JVM bytecode and cached in two tiers:</p>
    <ol>
      <li><strong>In-memory</strong> — Caffeine cache (500 entries, 1-hour TTL)</li>
      <li><strong>Persistent</strong> — compiled bytecode stored in the platform database with a compiler fingerprint (SHA-256 of Kotlin version + JVM target)</li>
    </ol>
    <p>Compilation runs on a single-thread dispatcher with a mutex to prevent redundant parallel compilations of the same script.</p>

    <Callout type="info">
      For <strong>GraalVM native image</strong> builds (which can't embed the Kotlin compiler), a <strong>remote engine</strong> enqueues compilation/execution jobs to a JVM worker via the job queue and reads results back via PubSub + object storage.
    </Callout>

    <h2 id="graphql">
      GraphQL API
    </h2>
    <CodeBlock
      lang="graphql"
      :code="`# Query
query { scripts { all(type: TRIGGER) { id key name enabled } } }

# Execute inline
mutation { scripts { executeScript(id: &quot;uuid&quot;, input: { query: &quot;hello&quot; }) } }

# CRUD
mutation { scripts {
    addScript(script: { key: &quot;my-script&quot;, name: &quot;My Script&quot;, type: API, source: &quot;...&quot; }) { id }
    enableScript(id: &quot;uuid&quot;)
    disableScript(id: &quot;uuid&quot;)
} }`"
    />
  </div>
</template>
