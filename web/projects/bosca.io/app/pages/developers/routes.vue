<script setup lang="ts">
definePageMeta({ layout: 'developers' })

useSeoMeta({
  title: 'Routes',
  description: 'HTTP endpoints with @RouteController, Route<T>, APIRoute<T>, SSERoute<T>, Page, and ServerCall.'
})
</script>

<template>
  <div class="doc-content article">
    <h1>Routes</h1>
    <p class="subtitle">
      HTTP endpoints with <code>@RouteController</code>, <code>Route&lt;T&gt;</code>, <code>APIRoute&lt;T&gt;</code>, <code>SSERoute&lt;T&gt;</code>, <code>Page</code>, and <code>ServerCall</code>.
    </p>

    <h2 id="route-types">
      Route Types
    </h2>
    <table>
      <thead>
        <tr>
          <th>Base Class</th>
          <th>Response</th>
          <th>Use Case</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>Route&lt;T&gt;</code></td>
          <td>JSON-serialized <code>T</code></td>
          <td>Standard REST endpoints</td>
        </tr>
        <tr>
          <td><code>APIRoute&lt;T&gt;</code></td>
          <td>JSON with structured errors</td>
          <td>Public API endpoints</td>
        </tr>
        <tr>
          <td><code>SSERoute&lt;T&gt;</code></td>
          <td>Server-Sent Events stream</td>
          <td>Real-time streaming (AI chat, progress)</td>
        </tr>
        <tr>
          <td><code>Page</code></td>
          <td>Server-rendered HTML (JTE)</td>
          <td>Web pages</td>
        </tr>
      </tbody>
    </table>

    <h2 id="defining">
      Defining a Route
    </h2>
    <CodeBlock
      lang="kotlin"
      :code="`@RouteController(
    &quot;/api/v1/security/passkeys&quot;,
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED
)
class PasskeyList(private val securityService: SecurityService) : Route<List<PasskeyInfo>>() {

    override fun serializer(): KSerializer<List<PasskeyInfo>> =
        ListSerializer(PasskeyInfo.serializer())

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext
    ): List<PasskeyInfo>? {
        val principal = authenticationContext.principal() ?: return null
        return securityService.getCredentials(principal.asPrincipal(), CredentialType.PASSKEY).map { credential ->
            val attributes = credential.attributes as PasskeyCredentialAttributes
            PasskeyInfo(attributes.identifier, attributes.name, attributes.createdAt,
                attributes.lastUsedAt, attributes.transports)
        }
    }
}`"
    />

    <p>
      This excerpt uses <code>PasskeyInfo</code> from the existing passkey routes.
      The complete route and response model are in
      <code>bosca-core/security/src/main/kotlin/bosca/security/routes/passkey/PasskeyList.kt</code>.
      Import routing types from <code>bosca.routes</code> and <code>bosca.routes.annotations</code>,
      and HTTP types from <code>bosca.server</code>.
    </p>
    <h3 id="annotation">
      @RouteController
    </h3>
    <table>
      <thead>
        <tr>
          <th>Parameter</th>
          <th>Default</th>
          <th>Purpose</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>path</code></td>
          <td>—</td>
          <td>URL pattern</td>
        </tr>
        <tr>
          <td><code>method</code></td>
          <td><code>GET</code></td>
          <td><code>GET</code>, <code>POST</code>, <code>PUT</code>, <code>DELETE</code>, <code>PATCH</code>, <code>HEAD</code></td>
        </tr>
        <tr>
          <td><code>authentication</code></td>
          <td><code>OPTIONAL</code></td>
          <td><code>NONE</code>, <code>REQUIRED</code>, <code>OPTIONAL</code></td>
        </tr>
      </tbody>
    </table>

    <p>Return a value with an explicit serializer for JSON model responses. Returning null without a response produces 404; returning Unit without a response produces 204.</p>
    <h2 id="sse">
      SSE Routes
    </h2>
    <p>This illustrative route sends two status events and closes the stream. Import SSE types from <code>bosca.server.sse</code>.</p>
    <CodeBlock
      lang="kotlin"
      :code="`@RouteController(&quot;/api/v1/example/events&quot;, authentication = RouteAuthentication.REQUIRED)
class ExampleEvents : SSERoute<Unit>() {
    override suspend fun execute(
        session: ServerSSESession,
        authenticationContext: AuthenticationContext
    ): Unit {
        session.send(data = &quot;Started&quot;, event = &quot;status&quot;)
        session.send(data = &quot;Finished&quot;, event = &quot;status&quot;)
        session.close()
    }
}`"
    />

    <h2 id="server-call">
      ServerCall
    </h2>
    <CodeBlock
      lang="kotlin"
      title="Reading requests"
      :code="`val input = call.receive<MyInput>()            // JSON body
val params = call.receiveParameters()          // Form-urlencoded
val multipart = call.receiveMultipart()        // File uploads
val id = call.pathParameters[&quot;id&quot;]             // Path parameter
val page = call.request.queryParameters[&quot;page&quot;] // Query parameter`"
    />
    <CodeBlock
      lang="kotlin"
      title="Writing responses"
      :code="`call.respond(myObject, MyModel.serializer())    // 200 + JSON
call.respond(HttpStatusCode.Created, myObject, MyModel.serializer()) // 201 + JSON
call.respondRedirect(&quot;/other/path&quot;)             // 302 redirect
call.respondBytes(bytes, contentType, status)  // Raw bytes
call.respondStreaming(contentType, status) {    // Streaming
    // write chunks
}`"
    />

    <h2 id="path-params">
      Path Parameters
    </h2>
    <CodeBlock
      lang="kotlin"
      :code="`// Full segment match
@RouteController(&quot;/api/v1/items/{id}&quot;)

// Partial segment match
@RouteController(&quot;/api/v1/files/{name}.json&quot;)

// Tailcard — consumes remaining segments
@RouteController(&quot;/api/v1/files/{path...}&quot;)`"
    />

    <h2 id="auth-comparison">
      Authentication: Routes vs. GraphQL
    </h2>
    <p>
      <strong>Routes</strong> declare auth at the route level (<code>NONE</code>/<code>REQUIRED</code>/<code>OPTIONAL</code>).
      <strong>GraphQL</strong> injects a request authentication context into resolvers; protected fields explicitly use permission or group evaluators.
    </p>
    <p>Both use the shared authentication providers. Route authentication still needs domain authorization checks before accessing protected records.</p>
  </div>
</template>
