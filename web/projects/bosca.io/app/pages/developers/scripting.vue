<script setup lang="ts">
definePageMeta({ layout: 'developers' })

useSeoMeta({
  title: 'Scripting',
  description: 'Write Kotlin scripts that run inside the platform — triggered by pipelines, exposed as API endpoints, or used as AI agent tools. Scripts execute on a JVM with guardrails against common dangerous operations, but are not fully sandboxed.'
})
</script>

<template>
  <div class="doc-content article">
    <h1>Scripting</h1>
    <p class="subtitle">
      Write Kotlin scripts that run inside the platform — triggered by pipelines, exposed as API
      endpoints, or used as AI agent tools. Scripts execute on a JVM with guardrails
      against common dangerous operations, but are not fully sandboxed.
    </p>

    <h2 id="overview">
      Overview
    </h2>
    <p>
      Bosca includes an embedded <strong>Kotlin Script (KTS)</strong> engine. Scripts are <code>.bosca.kts</code> files evaluated by Kotlin's embedded compiler. They can resolve Bosca services and return values from a <code>main { }</code> block. Author scripts as trusted platform code: service calls do not automatically repeat API-controller authorization.
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
          <td>Soft-deleted on deletion, then purged by the retention job</td>
        </tr>
      </tbody>
    </table>

    <h2 id="writing-scripts">
      Writing Scripts
    </h2>
    <p>
      Scripts extend <code>BoscaScript</code> implicitly. They have access to a <code>context</code> object, the <code>authentication</code> context, a coroutine <code>scope</code>, and a <code>log</code> logger. Read typed input with <code>context.get(serializer, key)</code>. Use the <code>main {}</code> block for async work:
    </p>
    <CodeBlock
      lang="kotlin"
      title="Example: read input and return JSON"
      :code="`import kotlinx.serialization.builtins.serializer\n\nmain {
    val name = context.get(String.serializer(), &quot;name&quot;) ?: &quot;world&quot;
    buildJsonObject {
        put(&quot;message&quot;, &quot;Hello, &quot; + name)
    }
}`"
    />

    <p>
      Submit <code>{ "name": "Alex" }</code> as input;
      the result is <code>{ "message": "Hello, Alex" }</code>.
      The final value of <code>main { }</code> becomes the script result.
    </p>

    <Callout type="info">
      Scripts resolve services using <code>provide&lt;T&gt;()</code> — the same DI mechanism used by the rest of the platform. The full server classpath is available at compile time.
    </Callout>

    <h2 id="triggers">
      Running Scripts from Events
    </h2>
    <p>
      To run a script when a <strong>platform event</strong> fires, create a <strong>Pipeline</strong> whose accepted input type is the event, add an <strong>Execute Script</strong> node, and mark the pipeline <em>Active</em>. When the event fires, the pipeline is enqueued as a background job and executed asynchronously — and the same graph can filter, transform, and combine the event data before the script runs.
    </p>
    <p>
      Event-triggered pipelines execute under the configured pipelines service account. The Execute Script
      node receives the pipeline run's authentication and passes the node's inbound value as script
      input. Manual and API pipeline runs retain the caller's identity. An event payload is ordinary
      input here; read its fields with <code>context.get()</code>.
    </p>

    <h3>Available events</h3>
    <p>
      Choose an event type from the installed pipeline type catalog. Generated event dispatch uses
      the Kotlin class's fully qualified name, such as <code>bosca.content.collection.events.CollectionCreated</code>.
      Active matching pipelines are dispatched through durable background jobs.
    </p>

    <h2 id="api-scripts">
      API Scripts
    </h2>
    <p>
      Scripts with type <code>API</code> are exposed as HTTP endpoints at <code>/api/v1/s/{key}</code>:
    </p>
    <ul>
      <li><strong>POST</strong> — request body is parsed as JSON; read fields through <code>context.get()</code></li>
      <li><strong>GET</strong> — query parameters are converted to a JSON object</li>
      <li><strong>Public scripts</strong> — no authentication required</li>
      <li><strong>Non-public scripts</strong> — caller must have <code>EXECUTE</code> permission</li>
      <li><strong>Timeout</strong> — 30 seconds</li>
    </ul>

    <h2 id="tool-scripts">
      AI Agent Tools
    </h2>
    <p>
      Scripts with type <code>TOOL</code> can be exposed as <strong>AI agent tools</strong> through the
      agent tool integration. Supply an <code>inputSchema</code> describing the accepted JSON object
      and return the value from <code>main { }</code>. Set the schema on the script record; a Kotlin
      comment does not configure it. For example:
    </p>
    <CodeBlock
      lang="json"
      title="Example input schema"
      :code="`{
  &quot;type&quot;: &quot;object&quot;,
  &quot;properties&quot;: { &quot;name&quot;: { &quot;type&quot;: &quot;string&quot; } },
  &quot;required&quot;: [&quot;name&quot;]
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
    <p>The default class-loader allowlist includes:</p>
    <ul>
      <li><code>bosca.*</code>, <code>kotlin.*</code>, <code>kotlinx.*</code>, <code>java.lang.*</code>, <code>java.util.*</code>, <code>java.time.*</code>, <code>java.math.*</code>, <code>java.text.*</code>, <code>org.slf4j.Logger</code></li>
    </ul>
    <p>Hard-denied even within allowed prefixes: <code>ProcessBuilder</code>, <code>reflect.*</code>, <code>ClassLoader</code>, <code>java.io.*</code>, <code>java.nio.*</code>, <code>java.net.*</code>, <code>Runtime</code>, <code>Thread</code></p>

    <h3>Execution timeout</h3>
    <p>The local engine defaults to 600 seconds (configurable). GraphQL and remote execution impose a five-minute limit; API routes impose a 30-second limit.</p>

    <h3>Permissions</h3>
    <p>Scripts have group-based ACL via <code>ScriptPermission</code> (VIEW/EXECUTE/MANAGE). Script management operations use the admin group or the operation-specific permission checks.</p>

    <h2 id="compilation">
      Compilation &amp; Caching
    </h2>
    <p>Scripts are compiled to JVM bytecode and cached in two tiers:</p>
    <ol>
      <li><strong>In-memory</strong> — Caffeine cache (up to 500 entries, one hour after last access)</li>
      <li><strong>Persistent</strong> — compiled bytecode stored in the platform database with a compiler fingerprint (SHA-256 of the compiler version, Kotlin version, and JVM target)</li>
    </ol>
    <p>Compilation runs on a single-thread dispatcher with a mutex to prevent redundant parallel compilations of the same script.</p>

    <Callout type="info">
      For <strong>GraalVM native image</strong> builds (which can't embed the Kotlin compiler), a <strong>remote engine</strong> enqueues compilation/execution jobs to a JVM worker via the job queue and reads results back via PubSub + object storage.
    </Callout>

    <p>
      The local Compose stack uses a JVM runner for script execution. Saving a script and executing
      it are separate operations; the API routes also require type <code>API</code> and an enabled
      script. See <code>scripting/scripting/src/main/resources/graphql/scripts.graphqls</code>
      for the complete record input and mutations.
    </p>
    <h2 id="graphql">
      GraphQL API
    </h2>
    <CodeBlock
      lang="graphql"
      :code="`# Query
query ListScripts { scripts { all(type: TRIGGER) { id key name enabled } } }

# Execute a stored script with supplied variables
mutation ExecuteScript($id: UUID!, $input: JSON) {
  scripts { executeScript(id: $id, input: $input) }
}

# Create a script
mutation CreateScript($script: ScriptInput!) {
  scripts { addScript(script: $script) { id key enabled } }
}`"
    />
  </div>
</template>
