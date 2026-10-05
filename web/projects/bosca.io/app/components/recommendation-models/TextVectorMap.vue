<script setup lang="ts">
const choices = [
  { id: 'stars', title: 'The life cycle of stars', values: [0.8, 0.6] },
  { id: 'sports', title: 'Football results', values: [0, 1] }
] as const

const selectedId = ref<(typeof choices)[number]['id']>('stars')
const selected = computed(() => choices.find(choice => choice.id === selectedId.value) ?? choices[0])
const endX = computed(() => 45 + 120 * selected.value.values[0])
const endY = computed(() => 170 - 120 * selected.value.values[1])
const similarity = computed(() => 1 * selected.value.values[0] + 0 * selected.value.values[1])
</script>

<template>
  <section
    class="vector-map"
    aria-label="Interactive text embedding comparison"
  >
    <div class="map-copy">
      <span class="eyebrow">Compare two made-up text embeddings</span>
      <p>
        Our first article, <strong>“How stars form”</strong>, has the made-up vector
        <code>[1, 0]</code>. Pick another article to compare with it.
      </p>
      <div
        class="choices"
        role="group"
        aria-label="Choose a second article"
      >
        <button
          v-for="choice in choices"
          :key="choice.id"
          type="button"
          :aria-pressed="selectedId === choice.id"
          @click="selectedId = choice.id"
        >
          {{ choice.title }} <code>[{{ choice.values[0] }}, {{ choice.values[1] }}]</code>
        </button>
      </div>
      <div
        class="calculation"
        aria-live="polite"
      >
        <p>Multiply matching positions, then add:</p>
        <strong>
          1 × {{ selected.values[0] }} + 0 × {{ selected.values[1] }} = {{ similarity }}
        </strong>
        <p v-if="selectedId === 'stars'">
          The arrows point mostly the same way, so these two invented star articles get a high
          similarity score.
        </p>
        <p v-else>
          The arrows point in different directions, so this invented sports article gets a
          similarity score of zero against the star article.
        </p>
      </div>
    </div>
    <div class="map-figure">
      <svg
        viewBox="0 0 250 205"
        role="img"
        :aria-label="`Two invented text vectors: How stars form [1, 0] and ${selected.title} [${selected.values.join(', ')}]`"
      >
        <defs>
          <marker
            id="toy-source-arrow"
            markerWidth="8"
            markerHeight="8"
            refX="6"
            refY="3"
            orient="auto"
          >
            <path
              class="source-arrow"
              d="M0,0 L0,6 L6,3 Z"
            />
          </marker>
          <marker
            id="toy-selected-arrow"
            markerWidth="8"
            markerHeight="8"
            refX="6"
            refY="3"
            orient="auto"
          >
            <path
              class="selected-arrow"
              d="M0,0 L0,6 L6,3 Z"
            />
          </marker>
        </defs>
        <line
          class="axis"
          x1="45"
          y1="170"
          x2="224"
          y2="170"
        />
        <line
          class="axis"
          x1="45"
          y1="170"
          x2="45"
          y2="20"
        />
        <text
          class="axis-label"
          x="171"
          y="194"
        >number 1 →</text>
        <text
          class="axis-label"
          x="8"
          y="17"
        >number 2 ↑</text>
        <circle
          class="origin"
          cx="45"
          cy="170"
          r="4"
        />
        <line
          class="source-line"
          x1="45"
          y1="170"
          x2="165"
          y2="170"
          marker-end="url(#toy-source-arrow)"
        />
        <line
          class="selected-line"
          x1="45"
          y1="170"
          :x2="endX"
          :y2="endY"
          marker-end="url(#toy-selected-arrow)"
        />
        <text
          class="point-label"
          x="170"
          y="157"
        >first article</text>
        <text
          class="point-label"
          :x="endX + 7"
          :y="endY - 8"
        >chosen article</text>
      </svg>
      <p>Each arrow starts at zero. The two numbers say how far it goes right and up.</p>
    </div>
    <p class="map-note">
      These are invented two-number vectors. Real text-model coordinates are learned from text
      and do not have simple topic names. Similarity here is not a chance that Sam will like an
      article.
    </p>
  </section>
</template>

<style scoped>
.vector-map {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(245px, 0.8fr);
  gap: 20px;
  margin: 24px 0 30px;
  padding: clamp(18px, 3vw, 26px);
  border: 1px solid color-mix(in srgb, #84c032 42%, var(--line));
  border-radius: var(--r-md);
  background: color-mix(in srgb, #84c032 5%, var(--bg-0));
}
.eyebrow { display: block; margin-bottom: 8px; color: #84c032; font-size: 12px; font-weight: 750; letter-spacing: 0.08em; text-transform: uppercase; }
.map-copy > p { margin: 0 0 16px; color: var(--fg-2); }
.map-copy strong { color: var(--fg-0); }
.choices { display: grid; gap: 8px; }
.choices button { padding: 10px 12px; border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--bg-0); color: var(--fg-1); font: inherit; text-align: left; cursor: pointer; }
.choices button[aria-pressed="true"] { border-color: #84c032; background: color-mix(in srgb, #84c032 16%, var(--bg-0)); }
.choices button:hover { border-color: #84c032; }
.choices button:focus-visible { outline: 2px solid #84c032; outline-offset: 2px; }
.choices code { display: block; color: var(--fg-3); font-size: 12px; }
.calculation { margin-top: 18px; padding: 14px; border-radius: var(--r-sm); background: var(--bg-1); }
.calculation p { margin: 0 0 8px; color: var(--fg-2); }
.calculation strong { color: var(--fg-0); font-variant-numeric: tabular-nums; }
.calculation p:last-child { margin: 10px 0 0; }
.map-figure { align-self: start; padding: 12px; border: 1px solid var(--line); border-radius: var(--r-md); background: var(--bg-0); }
.map-figure svg { display: block; width: 100%; height: auto; overflow: visible; }
.map-figure p { margin: 0; color: var(--fg-3); font-size: 12px; }
.axis { stroke: var(--fg-4); stroke-width: 2; }
.axis-label, .point-label { fill: var(--fg-2); font-size: 10px; }
.origin { fill: var(--fg-3); }
.source-line { stroke: #84c032; stroke-width: 4; }
.source-arrow { fill: #84c032; }
.selected-line { stroke: #718dff; stroke-width: 4; }
.selected-arrow { fill: #718dff; }
.map-note { grid-column: 1 / -1; margin: 0; color: var(--fg-3); font-size: 12px; }
@media (max-width: 760px) {
  .vector-map { grid-template-columns: 1fr; }
  .map-figure { max-width: 360px; }
}
</style>
