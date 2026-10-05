<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'BML Live State & Actions — Interactivity Without Client Code',
  description: 'How BML islands, @click/@submit actions, per-instance component state, server-held sessions, and typed contracts make pages interactive without hand-written JavaScript.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'BML', path: '/discover/bml' },
  { name: 'Live state & actions', path: '/discover/bml/live-state' }
], '/og-bml.png')
</script>

<template>
  <DiscoverShell section-id="bml">
    <section class="page-hero">
      <p class="kicker load-1">
        Live state &amp; actions
      </p>
      <h1 class="load-2">
        Interactive pages, <em>no client code</em>
      </h1>
      <p class="section-sub load-3">
        Islands render on the server and the runtime can update them in place.
        Most need no client script — declarative actions cover them. A deferred
        island can render private content after a public page shell loads.
      </p>
    </section>

    <!-- ── The action loop ─────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The action loop
          </p>
          <h2>An event names <em>a server method</em></h2>
          <p class="section-sub">
            A live state is a serializable model a page provides, reflected by
            one view island. <code>@click</code> or <code>@submit</code> on any
            element names a method on it. When the event fires, the runtime
            posts the state to the server; the server runs the method,
            persists the new state, re-renders the island, and the runtime
            swaps the view in place — the same component code as the first
            render, so the markup stays consistent.
          </p>
          <ul class="point-list">
            <li>Arguments are decoded against the method's own parameter types — Kotlin expressions bind at render time, so loop variables work.</li>
            <li><code>form.&lt;field&gt;</code> reads the bound form live at event time; checkboxes become booleans.</li>
            <li>The data plane is ambient: model methods call <code>client()</code> directly. Passing <code>ctx</code> explicitly is also supported.</li>
            <li>The post-action re-render swaps the form too, so it clears itself after a successful submit.</li>
          </ul>
          <p class="section-sub">
            Islands render with the page by default. Use
            <code>render="deferred"</code> when private HTML should load after
            a public shell; a static <code>&lt;fallback&gt;</code> can appear
            while that request runs. The
            <NuxtLink to="/bml-reference/islands">islands reference</NuxtLink>
            covers props, retries, and identity changes.
          </p>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">chat-panel.bml</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">&lt;component</span> <span class="tok-attr">tag</span>=<span class="tok-str">"chat-panel"</span><span class="tok-tag">&gt;</span>
  <span class="tok-tag">&lt;prop</span> <span class="tok-attr">name</span>=<span class="tok-str">"channelId"</span> <span class="tok-attr">type</span>=<span class="tok-str">"String"</span> <span class="tok-attr">required</span><span class="tok-tag">/&gt;</span>
  <span class="tok-tag">&lt;prop</span> <span class="tok-attr">name</span>=<span class="tok-str">"messages"</span> <span class="tok-attr">type</span>=<span class="tok-str">"List&lt;myapp.MessageRow&gt;"</span> <span class="tok-attr">required</span><span class="tok-tag">/&gt;</span>

  <span class="tok-tag">&lt;script</span> <span class="tok-attr">server</span> <span class="tok-attr">provides</span>=<span class="tok-str">"panel"</span><span class="tok-tag">&gt;</span>
    <span class="tok-kt">myapp.ChatPanelModel(channelId, messages)</span>
  <span class="tok-tag">&lt;/script&gt;</span>

  <span class="tok-tag">&lt;island</span> <span class="tok-attr">name</span>=<span class="tok-str">"chat-view"</span> <span class="tok-attr">:key</span>=<span class="tok-str">"channelId"</span><span class="tok-tag">&gt;</span>
    <span class="tok-tag">&lt;for</span> <span class="tok-kt">m</span> <span class="tok-attr">in</span> <span class="tok-kt">panel.messages</span><span class="tok-tag">&gt;</span>
      <span class="tok-tag">&lt;chat-msg</span> <span class="tok-attr">:message</span>=<span class="tok-str">"m"</span><span class="tok-tag">/&gt;</span>
    <span class="tok-tag">&lt;/for&gt;</span>
    <span class="tok-tag">&lt;form</span> <span class="tok-attr">@submit</span>=<span class="tok-str">"panel.send(form.message)"</span><span class="tok-tag">&gt;</span>
      <span class="tok-tag">&lt;input</span> <span class="tok-attr">type</span>=<span class="tok-str">"text"</span> <span class="tok-attr">name</span>=<span class="tok-str">"message"</span> <span class="tok-attr">required</span><span class="tok-tag">/&gt;</span>
      <span class="tok-tag">&lt;button</span> <span class="tok-attr">type</span>=<span class="tok-str">"submit"</span><span class="tok-tag">&gt;</span>Send<span class="tok-tag">&lt;/button&gt;</span>
    <span class="tok-tag">&lt;/form&gt;</span>
  <span class="tok-tag">&lt;/island&gt;</span>
<span class="tok-tag">&lt;/component&gt;</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Models reach the data plane ─────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Models
          </p>
          <h2>State that can <em>act</em></h2>
          <p class="section-sub">
            A model method reaches the caller's authenticated GraphQL client
            ambiently — <code>client()</code> is in scope during any render or
            action — so toggling a bookmark or sending a message runs as the
            person viewing the page, with the same permissions they'd have
            anywhere else on the platform. No context threading.
          </p>
          <p class="section-sub">
            Components can declare their own live state too — one model
            <strong>per instance</strong>, so a card inside a
            <code>&lt;for&gt;</code> loop carries its own toggle without a
            page-level über-model.
          </p>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">ReaderMarkModel.kt</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-com">// @click="mark.toggle()" runs this on the server</span>
<span class="tok-attr">@Serializable</span>
<span class="tok-tag">class</span> <span class="tok-kt">ReaderMarkModel</span>(<span class="tok-tag">val</span> metadataId: String, <span class="tok-tag">var</span> markId: Long? = <span class="tok-kt">null</span>) {
    <span class="tok-tag">val</span> saved: Boolean <span class="tok-tag">get</span>() = markId != <span class="tok-kt">null</span>

    <span class="tok-tag">suspend fun</span> <span class="tok-kt">toggle</span>() {
        <span class="tok-tag">val</span> current = markId
        markId = <span class="tok-tag">if</span> (current == <span class="tok-kt">null</span>) {
            client().execute(AddMark, AddMark.Variables(metadataId))
            lookUpNewMarkId(metadataId)
        } <span class="tok-tag">else</span> {
            client().execute(DeleteMark, DeleteMark.Variables(id = current))
            <span class="tok-kt">null</span>
        }
    }
}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Server scope & contracts ────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Privacy &amp; escape hatches
          </p>
          <h2>State that <em>stays on the server</em></h2>
          <p class="section-sub">
            By default, live state has page scope: it round-trips through the
            page and a refresh starts from the server-rendered model. Use
            <code>scope="client-session"</code> to retain it for the current
            browser session, or <code>scope="server-session"</code> to keep the
            model's JSON on the server behind an opaque HttpOnly cookie. Server
            session scope suits private state such as carts and entitlements,
            or state that is too large to round-trip.
          </p>
          <p class="section-sub">
            When a flow doesn't fit the action model, declare a
            <code>&lt;contract&gt;</code>: a Kotlin interface in the page file.
            The compiler emits a server dispatcher and a typed TypeScript stub,
            and client code calls the method as an ordinary typed async
            function.
          </p>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">a contract and its typed client call</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">&lt;contract&gt;</span>
  <span class="tok-kt">interface GroupChatOps {</span>
      <span class="tok-kt">suspend fun updateLastRead(channelId: String, sequence: Long)</span>
  <span class="tok-kt">}</span>
<span class="tok-tag">&lt;/contract&gt;</span>

<span class="tok-tag">&lt;script</span> <span class="tok-attr">client</span><span class="tok-tag">&gt;</span>
  <span class="tok-kt">import { GroupChatOps } from "./GroupChatOps"</span>

  <span class="tok-com">// The generated typed stub — a plain async call.</span>
  <span class="tok-kt">GroupChatOps.updateLastRead(channelId, Number(maxSequence))</span>
<span class="tok-tag">&lt;/script&gt;</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <DiscoverBmlExplore />
  </DiscoverShell>
</template>
