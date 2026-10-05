<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Feed Serving — Following, For-You & Recommendations',
  description: 'Readers subscribe to sources and get a chronological Following feed; the For-you feed is ranked by the Recommendations subsystem. All three serving surfaces return items as ordinary platform content.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Feeds', path: '/discover/feeds' },
  { name: 'Serving', path: '/discover/feeds/serving' }
], '/og-feeds.png')
</script>

<template>
  <DiscoverShell section-id="feeds">
    <section class="page-hero">
      <p class="kicker load-1">
        Serving &amp; subscriptions
      </p>
      <h1 class="load-2">
        Following for everyone, <em>For-you for each one</em>
      </h1>
      <p class="section-sub load-3">
        Feeds owns the serving surface and stays out of the ranking business —
        the chronological feed is assembled from what a reader follows, and
        the personalized feed is ranked by the Recommendations subsystem.
      </p>
    </section>

    <!-- ── Subscriptions ───────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Subscriptions
          </p>
          <h2>Follow a source, <em>own a source</em></h2>
          <p class="section-sub">
            A subscription links a reader's profile to a source. Together
            with the sources they own outright, it defines what their
            Following feed contains.
          </p>
          <ul class="point-list">
            <li><code>subscribe(sourceId)</code> is idempotent — calling it twice is safe and returns the subscribed source either way.</li>
            <li><code>unsubscribe(sourceId)</code> is honest — it returns <code>false</code> when the caller wasn't subscribed.</li>
            <li><code>mySubscriptions</code> lists the caller's subscribed sources, newest subscription first; <code>mySources</code> lists the ones they own.</li>
            <li>User-owned sources contribute to their owner's feed automatically — no self-subscription needed.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">subscribe</span>
          </div>
          <div class="code-body">
            <pre><span class="tok-kt">mutation</span> Subscribe($sourceId: <span class="tok-interp">UUID!</span>) {
  feeds {
    mySubscriptions {
      <span class="tok-attr">subscribe</span>(sourceId: $sourceId) {
        id
        name
      }
    }
  }
}</pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Serving surfaces ────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Serving surfaces
          </p>
          <h2>Three feeds, <em>one content model</em></h2>
          <p class="section-sub">
            Every surface returns items as content records — so a client
            reads the normalized body, the attributes, and the featured-image
            relationship the same way it reads any other content.
          </p>
          <ul class="point-list">
            <li><code>myFeed</code> — the assembled Following feed: items from subscribed and owned sources, newest first.</li>
            <li><code>forYou</code> — the personalized feed, ranked by the Recommendations subsystem for this reader.</li>
            <li><code>recommended(metadataId)</code> — item-context recommendations for a given article, re-ranked for the caller.</li>
            <li>Ordering is preserved through resolution — render items in the order the server returns them; items whose content no longer exists are dropped.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">read the feeds</span>
          </div>
          <div class="code-body">
            <pre><span class="tok-kt">query</span> MyFeeds {
  feeds {
    <span class="tok-attr">myFeed</span>(offset: <span class="tok-interp">0</span>, limit: <span class="tok-interp">25</span>) {
      id name attributes
    }
    <span class="tok-attr">forYou</span>(offset: <span class="tok-interp">0</span>, limit: <span class="tok-interp">25</span>) {
      id name attributes
    }
  }
}</pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── The ranking seam & Studio ───────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The ranking seam
          </p>
          <h2>Feeds serves, <em>Recommendations ranks</em></h2>
          <p class="section-sub">
            The boundary is deliberate: ingested items become recommendable
            through the standard content lifecycle events, and the
            personalized surfaces delegate to the recommendations service.
          </p>
          <ul class="point-list">
            <li><code>forYou</code> and <code>recommended</code> hand ranking to the Recommendations subsystem, then resolve the resulting ids back to content.</li>
            <li>There's no feeds-specific wiring to configure — recommendations reacts to the same lifecycle events all content emits.</li>
            <li>For inspection, <code>feeds.items(sourceId)</code> lists a source's ingested items newest-first, gated on the feeds admin group.</li>
            <li>Studio's Preview page is a reader over that surface — pick a source, browse its items, and see each stored body rendered exactly as it will be served.</li>
          </ul>
        </div>
        <div class="integ-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">connected</span>
          </div>
          <div class="integ-body">
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="inbox"
                :size="15"
              /></span>
              <span class="integ-text"><code>myFeed</code> → subscribed + owned, newest first</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="sparkles"
                :size="15"
              /></span>
              <span class="integ-text"><code>forYou</code> → ranked by Recommendations</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="link"
                :size="15"
              /></span>
              <span class="integ-text"><code>recommended(id)</code> → related, re-ranked</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="monitor"
                :size="15"
              /></span>
              <span class="integ-text">Studio → Feeds → <code>Preview</code> reader</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverFeedsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Integration window ──────────────────────── */

.integ-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.integ-body {
  padding: 10px 8px;
}

.integ-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
}

.integ-row + .integ-row {
  border-top: 1px solid var(--line);
}

.integ-icon {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: #d3f4fb;
}

.integ-text {
  font-size: 13px;
  color: var(--fg-1);
}

.integ-text code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}
</style>
