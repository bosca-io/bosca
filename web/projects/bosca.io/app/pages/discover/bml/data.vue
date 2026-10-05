<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'BML Data & Auth — The Typed GraphQL Data Plane',
  description: 'How a BML site talks to Bosca: typed GraphQL clients on both sides, a same-origin proxy, client-managed auth with token passthrough, and the render context.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'BML', path: '/discover/bml' },
  { name: 'Data & auth', path: '/discover/bml/data' }
], '/og-bml.png')

const CONTEXT = [
  { snippet: 'ctx.gql', body: 'The token-bound GraphQL client for this request. Never null.' },
  { snippet: 'ctx.params', body: 'Matched route parameters — {id} from /group/{id}.' },
  { snippet: 'ctx.query', body: 'Query-string parameters, first value per name.' },
  { snippet: 'ctx.cookies', body: 'The request\'s cookies, readable during SSR.' },
  { snippet: 'ctx.token', body: 'The caller\'s resolved passthrough token, when any.' },
  { snippet: 'ctx.redirectTo', body: 'Short-circuit the render into a 302 redirect.' }
]
</script>

<template>
  <DiscoverShell section-id="bml">
    <section class="page-hero">
      <p class="kicker load-1">
        Data &amp; auth
      </p>
      <h1 class="load-2">
        Every query runs <em>as the viewer</em>
      </h1>
      <p class="section-sub load-3">
        All data access goes through the Bosca GraphQL API — a BML site never
        touches Bosca services directly. The server holds no auth library at
        all: the browser owns the token, and the server passes it through.
      </p>
    </section>

    <!-- ── Typed GraphQL ───────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Typed operations
          </p>
          <h2>GraphQL files become <em>Kotlin symbols</em></h2>
          <p class="section-sub">
            Operations live as <code>.graphql</code> files in the project and
            compile to typed Kotlin — a server script calls
            <code>ctx.gql.execute(GetMyGroups, Unit)</code> and gets a typed
            result. The client on <code>ctx.gql</code> is minted per request
            and bound to the caller's token, so every query runs with the
            viewer's own permissions.
          </p>
          <ul class="point-list">
            <li>Browser code calls the same API through a same-origin <code>/graphql</code> proxy — no CORS, no tokens configured in client code.</li>
            <li>The proxy forwards the request body untouched and attaches the caller's resolved token; upstream status and JSON pass through as-is.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">Home.kt — a page's data function</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">suspend fun</span> <span class="tok-kt">home</span>(ctx: RenderContext): HomePage {
    <span class="tok-tag">if</span> (ctx.token == <span class="tok-kt">null</span>) <span class="tok-tag">return</span> HomePage(emptyList(), signedIn = <span class="tok-kt">false</span>)
    <span class="tok-tag">val</span> groups = ctx.gql.execute(GetMyGroups, Unit)
        .profiles.current?.firstOrNull()
        ?.community?.groups.orEmpty()
    <span class="tok-tag">return</span> HomePage(groups, signedIn = <span class="tok-kt">true</span>)
}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Auth model ──────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Auth
          </p>
          <h2>The browser owns the token. <em>The server forwards it.</em></h2>
          <p class="section-sub">
            The browser auth library owns the Bosca JWT; the server never
            mints, refreshes, or inspects tokens. For each request it resolves
            the token from the <code>Authorization</code> header when present,
            else from the auth SDK's cookie — page navigations send no header,
            so the cookie covers them.
          </p>
          <ul class="point-list">
            <li><code>&lt;page … requireAuth&gt;</code> — a token-less caller never renders the page and is redirected to the configured sign-in route.</li>
            <li><code>ctx.redirectTo</code> — a server script can short-circuit any render into a redirect for finer-grained guards.</li>
            <li>Invalid tokens are rejected by the data plane itself — the site doesn't re-implement validation.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">reading the context</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">val</span> id = ctx.params[<span class="tok-str">"id"</span>].orEmpty()          <span class="tok-com">// /group/{id}</span>
<span class="tok-tag">val</span> status = ctx.query[<span class="tok-str">"status"</span>] ?: <span class="tok-str">"all"</span>    <span class="tok-com">// ?status=answered</span>
<span class="tok-tag">val</span> theme = ctx.cookies[<span class="tok-str">"appearance"</span>]        <span class="tok-com">// read during SSR</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── The render context ──────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          The render context
        </p>
        <h2>One <em>ctx</em> for the whole request</h2>
        <p class="section-sub">
          Every render receives a <code>RenderContext</code> — in scope in
          server scripts and expressions.
        </p>
      </div>
      <div class="ctx-grid reveal">
        <div
          v-for="item in CONTEXT"
          :key="item.snippet"
          class="ctx-card"
        >
          <code>{{ item.snippet }}</code>
          <p>{{ item.body }}</p>
        </div>
      </div>
    </section>

    <DiscoverBmlExplore />
  </DiscoverShell>
</template>

<style scoped>
.ctx-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.ctx-card {
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.ctx-card > code {
  display: inline-block;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--accent);
  margin-bottom: 10px;
}

.ctx-card p {
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0;
}

@media (max-width: 960px) {
  .ctx-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .ctx-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
