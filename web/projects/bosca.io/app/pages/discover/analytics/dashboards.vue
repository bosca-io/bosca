<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Analytics Visualizations & Dashboards — Charts on a Grid',
  description: 'Bind a query to one of ten visualization types, then arrange visualizations on a drag-and-drop dashboard grid with per-tile overrides and shared, typed parameters that flow into each chart by name.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Analytics', path: '/discover/analytics' },
  { name: 'Visualizations & dashboards', path: '/discover/analytics/dashboards' }
], '/og-analytics.png')

const VIZ_TYPES = [
  { name: 'Number', note: 'single value' },
  { name: 'Bar', note: 'x / y' },
  { name: 'Line', note: 'x / y' },
  { name: 'Scatter', note: 'x / y' },
  { name: 'Bubble', note: 'x / y / size' },
  { name: 'Pie', note: 'label / value' },
  { name: 'Doughnut', note: 'label / value' },
  { name: 'Stacked Area', note: 'cumulative' },
  { name: 'Table', note: 'columns' },
  { name: 'Label', note: 'static text' },
  { name: 'Date Picker', note: 'drives params' },
  { name: 'Gantt', note: 'timeline' },
  { name: 'Graph', note: 'relationships' },
  { name: 'Region Map', note: 'choropleth' },
  { name: 'Point Map', note: 'lat / long' },
  { name: 'Live Map', note: 'live sessions' }
]
</script>

<template>
  <DiscoverShell section-id="analytics">
    <section class="page-hero">
      <p class="kicker load-1">
        Visualizations &amp; dashboards
      </p>
      <h1 class="load-2">
        A chart is <em>a query, drawn</em>
      </h1>
      <p class="section-sub load-3">
        A visualization is a named binding of a query to a visualization type,
        plus the configuration that type needs. Lay several out on a dashboard
        grid, wire them to shared parameters, and you have a board that moves as
        one.
      </p>
    </section>

    <!-- ── Visualization types ─────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Visualization types
        </p>
        <h2>A type for <em>every shape</em></h2>
        <p class="section-sub">
          Pick a type, point it at a query, and map the columns — from a single
          number to a live session map, the editor shows a live preview as you
          go, and a JSON view stays in sync with the typed fields.
        </p>
      </div>
      <div class="viz-grid reveal">
        <div
          v-for="viz in VIZ_TYPES"
          :key="viz.name"
          class="viz-card"
        >
          <h3>{{ viz.name }}</h3>
          <span class="viz-note">{{ viz.note }}</span>
        </div>
      </div>
    </section>

    <!-- ── The grid ────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The dashboard grid
          </p>
          <h2>Drag, drop, <em>resize</em></h2>
          <p class="section-sub">
            A dashboard is a grid of visualization tiles. Drag them and resize
            them, and the positions and sizes save with the dashboard — so the
            layout you build is the layout everyone sees.
          </p>
          <ul class="point-list">
            <li>Per-tile overrides: a title override and size, and toggles for the title, a border, and a background.</li>
            <li>Add a visualization from a dropdown; remove one and it comes off the board.</li>
            <li>Expand the grid to full width when you're arranging a dense board.</li>
          </ul>
        </div>
        <div class="dash-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">home dashboard</span>
          </div>
          <div class="dash-grid">
            <div class="tile tile-number">
              <span class="tile-label">Sessions</span>
              <span class="tile-big">48.2k</span>
            </div>
            <div class="tile tile-number">
              <span class="tile-label">Completions</span>
              <span class="tile-big">6,904</span>
            </div>
            <div class="tile tile-chart">
              <span class="tile-label">Sessions / day</span>
              <div class="mini-bars">
                <span style="height: 40%;" /><span style="height: 62%;" /><span style="height: 50%;" /><span style="height: 78%;" /><span style="height: 66%;" /><span style="height: 90%;" />
              </div>
            </div>
            <div class="tile tile-table">
              <span class="tile-label">Top apps</span>
              <span class="tile-line" /><span class="tile-line" /><span class="tile-line short" />
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Parameters propagate ────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Shared parameters
          </p>
          <h2>One control, <em>the whole board</em></h2>
          <p class="section-sub">
            A dashboard declares its own typed parameters. When it runs each
            visualization, it passes only the parameters that chart's query asks
            for — matched by name. Drop a date picker on the board and every
            chart that accepts a date range moves together.
          </p>
          <ul class="point-list">
            <li>Supply is the dashboard's parameters; demand is each query's parameters — they meet by name.</li>
            <li>The same board becomes a single-app view or a fleet view by changing its inputs, not its charts.</li>
          </ul>
        </div>
        <div class="prop-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">parameters flow</span>
          </div>
          <div class="prop-body">
            <div class="prop-source">
              <Icon
                name="calendar"
                :size="15"
                class="prop-icon"
              />
              <span>date picker → <span class="prop-k">from</span>, <span class="prop-k">to</span></span>
            </div>
            <div class="prop-arrow">
              <Icon
                name="arrow-right"
                :size="15"
              />
            </div>
            <div class="prop-targets">
              <span class="prop-target">Sessions / day <span class="prop-ok">from, to ✓</span></span>
              <span class="prop-target">Top apps <span class="prop-ok">from, to ✓</span></span>
              <span class="prop-target prop-skip">Live number <span class="prop-none">— no date param</span></span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverAnalyticsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Visualization type cards ────────────────── */

.viz-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.viz-card {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 16px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.viz-card:hover {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  transform: translateY(-2px);
}

.viz-card h3 {
  font-size: 14px;
  font-weight: 650;
  margin: 0;
}

.viz-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Dashboard grid mockup ───────────────────── */

.dash-window,
.prop-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.dash-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  padding: 16px;
}

.tile {
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 84px;
}

.tile-label {
  font-family: var(--font-mono);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-3);
}

.tile-big {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--fg-0);
}

.tile-chart .mini-bars {
  display: flex;
  align-items: flex-end;
  gap: 5px;
  height: 44px;
}

.tile-chart .mini-bars span {
  flex: 1;
  border-radius: 3px 3px 0 0;
  background: linear-gradient(180deg, var(--accent), color-mix(in srgb, var(--accent) 40%, transparent));
}

.tile-line {
  height: 8px;
  border-radius: 4px;
  background: color-mix(in srgb, var(--fg-3) 16%, transparent);
}

.tile-line.short {
  width: 60%;
}

/* ── Parameter propagation ───────────────────── */

.prop-body {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.prop-source {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  font-size: 13px;
  color: var(--fg-1);
}

.prop-icon {
  color: var(--accent);
}

.prop-k {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent);
}

.prop-arrow {
  display: flex;
  justify-content: center;
  color: var(--accent);
  transform: rotate(90deg);
}

.prop-targets {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.prop-target {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 11px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  font-size: 13px;
  color: var(--fg-1);
}

.prop-ok {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--accent);
}

.prop-skip {
  opacity: 0.6;
}

.prop-none {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 960px) {
  .viz-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .viz-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
