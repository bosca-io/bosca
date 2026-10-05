<script setup lang="ts">
const candidates = [
  { id: 'article-12', title: 'The life cycle of stars', category: 'science', science: 1, sports: 0 },
  { id: 'article-14', title: 'The championship match', category: 'sports', science: 0, sports: 1 }
] as const
const futureCandidates = [
  { id: 'article-21', title: 'Why comets glow', category: 'science', science: 1, sports: 0 },
  { id: 'article-22', title: 'Inside the final whistle', category: 'sports', science: 0, sports: 1 }
] as const

type CandidateId = typeof candidates[number]['id']
type Outcome = 'completed' | 'dismissed'

const scienceWeight = ref(1.5)
const sportsWeight = ref(1)
const chosenId = ref<CandidateId | null>(null)
const outcome = ref<Outcome | null>(null)

type ArticleFeatures = { science: number, sports: number }
const scoreWith = (article: ArticleFeatures, science: number, sports: number) =>
  science * article.science + sports * article.sports
const score = (article: ArticleFeatures) => scoreWith(article, scienceWeight.value, sportsWeight.value)

const chosen = computed(() => candidates.find(candidate => candidate.id === chosenId.value))
const leader = computed(() => {
  const [stars, sports] = candidates
  if (score(stars) === score(sports)) return null
  return score(stars) > score(sports) ? stars : sports
})
const updateAmount = computed(() => outcome.value === 'completed' ? 1 : outcome.value === 'dismissed' ? -1 : 0)
const nextScienceWeight = computed(() => Math.max(0, scienceWeight.value + (chosen.value?.category === 'science' ? updateAmount.value : 0)))
const nextSportsWeight = computed(() => Math.max(0, sportsWeight.value + (chosen.value?.category === 'sports' ? updateAmount.value : 0)))
const nextScore = (article: ArticleFeatures) => scoreWith(article, nextScienceWeight.value, nextSportsWeight.value)
const futureLeaderBefore = computed(() => {
  const [scienceArticle, sportsArticle] = futureCandidates
  if (score(scienceArticle) === score(sportsArticle)) return null
  return score(scienceArticle) > score(sportsArticle) ? scienceArticle : sportsArticle
})
const futureLeaderAfter = computed(() => {
  const [scienceArticle, sportsArticle] = futureCandidates
  if (nextScore(scienceArticle) === nextScore(sportsArticle)) return null
  return nextScore(scienceArticle) > nextScore(sportsArticle) ? scienceArticle : sportsArticle
})

watch([scienceWeight, sportsWeight], () => {
  chosenId.value = null
  outcome.value = null
})

function choose(id: CandidateId) {
  chosenId.value = id
  outcome.value = null
}
</script>

<template>
  <section
    class="playground"
    aria-label="Interactive recommendation example"
  >
    <div class="playground-heading">
      <span class="eyebrow">Try the decision yourself</span>
      <h3>Choose an article, then score two more</h3>
      <p>
        Sam (<code>reader-1</code>) completed a science article earlier and dismissed a sports
        article. First, use a simple rule to choose between two new articles. Then record
        Sam's response and see how it changes the scores of articles that arrive later.
      </p>
    </div>

    <div class="stage">
      <h4><span class="stage-number">1</span> Start with what we know</h4>
      <p>Both articles are in English, so language does not help us choose between them. We will use their categories. For each question below, <strong>1 means yes</strong> and <strong>0 means no</strong>.</p>
      <div class="feature-table-wrap">
        <table class="feature-table">
          <thead>
            <tr>
              <th scope="col">
                New article
              </th><th scope="col">
                Science?
              </th><th scope="col">
                Sports?
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="candidate in candidates"
              :key="candidate.id"
            >
              <th scope="row">
                {{ candidate.title }}
              </th>
              <td>{{ candidate.science }}</td>
              <td>{{ candidate.sports }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="explain">
        The stars article has Science? = 1 because it is about science, and Sports? = 0 because it is not about sports. These numbers are <strong>feature values</strong>: facts we know before Sam sees the article. We do not know Sam's response yet.
      </p>
    </div>

    <div class="stage">
      <h4><span class="stage-number">2</span> Decide how much each category counts</h4>
      <p>A <strong>weight</strong> says how many points a category adds when its feature value is 1. We start science at 1.5 points and sports at 1 point. These are example numbers, not numbers learned from Sam's history.</p>
      <div
        class="controls"
        aria-label="Category weights"
      >
        <div class="control">
          <div class="control-title">
            <label for="toy-science-weight">Science weight</label>
            <output for="toy-science-weight">{{ scienceWeight }} points</output>
          </div>
          <input
            id="toy-science-weight"
            v-model.number="scienceWeight"
            type="range"
            min="0"
            max="3"
            step="0.5"
          >
          <p>Added when Science? is 1</p>
        </div>
        <div class="control">
          <div class="control-title">
            <label for="toy-sports-weight">Sports weight</label>
            <output for="toy-sports-weight">{{ sportsWeight }} points</output>
          </div>
          <input
            id="toy-sports-weight"
            v-model.number="sportsWeight"
            type="range"
            min="0"
            max="3"
            step="0.5"
          >
          <p>Added when Sports? is 1</p>
        </div>
      </div>
      <p class="rule">
        <strong>Scoring rule:</strong> science weight × Science? + sports weight × Sports?
      </p>
      <p class="explain">
        Multiplying by 1 keeps a category's points; multiplying by 0 gives zero. Add the results for the article's score. Try giving sports more points than science, then make them equal. Changing a slider starts a new choice.
      </p>
    </div>

    <div class="stage">
      <h4><span class="stage-number">3</span> Compare the scores and choose</h4>
      <p>Here is the same rule filled in for each article. A higher score puts an article ahead in <em>this exercise</em>. The score is not a probability that Sam will like it.</p>
      <div class="candidates">
        <article
          v-for="candidate in candidates"
          :key="candidate.id"
          class="candidate"
          :class="{ 'is-leading': leader?.id === candidate.id }"
        >
          <span class="candidate-id">{{ candidate.id }}</span>
          <h5>{{ candidate.title }}</h5>
          <p class="calculation">
            {{ scienceWeight }} × {{ candidate.science }} + {{ sportsWeight }} × {{ candidate.sports }} = <strong>{{ score(candidate) }}</strong>
          </p>
          <p class="score-meaning">
            {{ scienceWeight * candidate.science }} science points + {{ sportsWeight * candidate.sports }} sports points
          </p>
          <button
            type="button"
            :aria-pressed="chosenId === candidate.id"
            @click="choose(candidate.id)"
          >
            {{ chosenId === candidate.id ? 'Chosen for Sam' : 'Show this to Sam' }}
          </button>
        </article>
      </div>
      <p
        class="score-hint"
        aria-live="polite"
      >
        <template v-if="leader">
          {{ leader.title }} scores higher, so this rule would put it first. You can choose either article to continue.
        </template>
        <template v-else>
          The articles are tied. This rule alone cannot decide which to show first; you can choose either one.
        </template>
      </p>
    </div>

    <div
      class="stage response-stage"
      aria-live="polite"
    >
      <h4><span class="stage-number">4</span> Record what Sam does</h4>
      <template v-if="!chosen">
        <p>Choose an article above. Its category and score are known now; Sam's response will be known only after the article is shown.</p>
      </template>
      <template v-else-if="!outcome">
        <p>Sam sees <strong>{{ chosen.title }}</strong>. Imagine what Sam does next:</p>
        <div class="outcome-actions">
          <button
            type="button"
            @click="outcome = 'completed'"
          >
            Sam completes it
          </button>
          <button
            type="button"
            @click="outcome = 'dismissed'"
          >
            Sam dismisses it
          </button>
        </div>
      </template>
      <template v-else>
        <p>Sam <strong>{{ outcome }}</strong> {{ chosen.title }}. Now we have a past example:</p>
        <p class="example-row">
          Reader ID: <code>reader-1</code><br>
          Article ID: <code>{{ chosen.id }}</code><br>
          Label: <code>{{ outcome === 'completed' ? 1 : 0 }}</code>
        </p>
        <p>A <strong>label</strong> is the answer we record for training. In this exercise, 1 means completed and 0 means dismissed. We could not use this answer to score the article before Sam acted. Now use it to change the rule for later articles.</p>
        <button
          type="button"
          class="again"
          @click="outcome = null"
        >
          Try the other response
        </button>
      </template>
    </div>
    <div class="stage future-stage">
      <h4><span class="stage-number">5</span> Score the next pair of articles</h4>
      <template v-if="!chosen || !outcome">
        <p>After you record Sam's response above, you will see how it changes a future choice. To see the top article switch, leave the starting weights at 1.5 and 1, show Sam the stars article, and choose “Sam dismisses it.”</p>
      </template>
      <template v-else>
        <p>Here is our <strong>made-up learning rule</strong>: if Sam completes the article, add 1 point to its category's weight. If Sam dismisses it, subtract 1 point from that weight, stopping at 0. The other category's weight stays the same.</p>
        <p>Sam {{ outcome }} a <strong>{{ chosen.category }}</strong> article, so the weights change like this:</p>
        <div class="weight-changes">
          <p>Science: <strong>{{ scienceWeight }} → {{ nextScienceWeight }}</strong></p>
          <p>Sports: <strong>{{ sportsWeight }} → {{ nextSportsWeight }}</strong></p>
        </div>
        <p>Now two <strong>different</strong> articles arrive. One is science and one is sports, so they use the same 1-or-0 category values as the first pair. Apply the same scoring formula with the updated weights:</p>
        <div class="feature-table-wrap">
          <table class="feature-table future-table">
            <thead>
              <tr>
                <th scope="col">
                  Later article
                </th>
                <th scope="col">
                  Category
                </th>
                <th scope="col">
                  Score before
                </th>
                <th scope="col">
                  New calculation
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="article in futureCandidates"
                :key="article.id"
              >
                <th scope="row">
                  {{ article.title }}
                </th>
                <td>{{ article.category }}</td>
                <td>{{ score(article) }}</td>
                <td class="after-score">
                  {{ nextScienceWeight }} × {{ article.science }} + {{ nextSportsWeight }} × {{ article.sports }} = <strong>{{ nextScore(article) }}</strong>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="future-result">
          <template v-if="futureLeaderBefore">
            Before Sam responded, {{ futureLeaderBefore.title }} would have ranked first.
          </template>
          <template v-else>
            Before Sam responded, the two later articles would have been tied.
          </template>
          <template v-if="futureLeaderAfter">
            With the updated weights, {{ futureLeaderAfter.title }} ranks first.
          </template>
          <template v-else>
            With the updated weights, the two later articles are tied.
          </template>
        </p>
        <p>We changed the weight right away to make the effect visible. Bosca stores responses as examples. In a later training run, it uses many examples to adjust many weights; a newly trained model can then change future recommendations. The real trainer does not use this one-point rule, and one response does not guarantee a different top article.</p>
      </template>
    </div>
    <p class="toy-note">
      The articles, starting weights, and response rule in this exercise are invented to make the feedback loop visible.
    </p>
  </section>
</template>

<style scoped>
.playground {
  margin: 24px 0 36px;
  padding: clamp(18px, 3vw, 30px);
  border: 1px solid color-mix(in srgb, #84c032 42%, var(--line));
  border-radius: var(--r-md);
  background: color-mix(in srgb, #84c032 5%, var(--bg-0));
}
.eyebrow {
  display: block;
  margin-bottom: 10px;
  color: #84c032;
  font-size: 12px;
  font-weight: 750;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.playground-heading h3 { margin: 0 0 8px; color: var(--fg-0); font-size: clamp(20px, 2.5vw, 27px); }
.playground-heading p { margin: 0; color: var(--fg-2); }
.stage { padding: 24px 0; border-top: 1px solid var(--line); }
.playground-heading + .stage { margin-top: 24px; }
.stage h4 { display: flex; align-items: center; gap: 10px; margin: 0 0 10px; color: var(--fg-0); font-size: 18px; }
.stage-number { display: inline-grid; flex: none; width: 27px; height: 27px; place-items: center; border-radius: 50%; background: #84c032; color: #17220c; font-size: 13px; font-weight: 800; }
.stage p { margin: 0 0 14px; color: var(--fg-2); }
.stage p:last-child { margin-bottom: 0; }
.stage strong { color: var(--fg-0); }
.feature-table-wrap { overflow-x: auto; margin: 16px 0; }
.feature-table { width: 100%; border-collapse: collapse; background: var(--bg-0); color: var(--fg-1); }
.feature-table th, .feature-table td { padding: 10px 12px; border: 1px solid var(--line); text-align: left; }
.feature-table thead th { color: var(--fg-0); font-size: 13px; }
.feature-table tbody th { font-weight: 550; }
.feature-table td { width: 18%; font-variant-numeric: tabular-nums; }
.stage .explain { margin-bottom: 0; font-size: 14px; }
.controls, .candidates { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.control, .candidate { padding: 16px; border: 1px solid var(--line); border-radius: var(--r-md); background: var(--bg-0); }
.control-title { display: flex; justify-content: space-between; gap: 10px; align-items: baseline; }
.control label { color: var(--fg-0); font-weight: 650; }
.control output { color: #84c032; font-size: 18px; font-weight: 750; font-variant-numeric: tabular-nums; white-space: nowrap; }
.control input { width: 100%; margin: 14px 0 6px; accent-color: #84c032; cursor: pointer; }
.control p { margin: 0; font-size: 13px; }
.stage .rule { margin: 16px 0 8px; padding: 12px 14px; border-radius: var(--r-sm); background: var(--bg-1); color: var(--fg-0); font-variant-numeric: tabular-nums; }
.candidate { display: flex; flex-direction: column; gap: 9px; }
.candidate.is-leading { border-color: color-mix(in srgb, #84c032 65%, var(--line)); }
.candidate-id { color: var(--fg-3); font-size: 12px; font-family: monospace; }
.candidate h5 { margin: 0; color: var(--fg-0); font-size: 17px; }
.candidate .calculation { margin: 4px 0 0; color: var(--fg-0); font-variant-numeric: tabular-nums; }
.candidate .score-meaning { margin: 0 0 8px; font-size: 13px; }
.candidate button, .response-stage button { padding: 10px 14px; border: 1px solid color-mix(in srgb, #84c032 65%, var(--line)); border-radius: var(--r-sm); background: color-mix(in srgb, #84c032 11%, var(--bg-0)); color: var(--fg-0); font: inherit; font-weight: 650; cursor: pointer; }
.candidate button { margin-top: auto; }
.candidate button:hover, .response-stage button:hover { background: color-mix(in srgb, #84c032 20%, var(--bg-0)); }
.candidate button:focus-visible, .response-stage button:focus-visible, .control input:focus-visible { outline: 2px solid #84c032; outline-offset: 2px; }
.candidate button[aria-pressed="true"] { background: color-mix(in srgb, #84c032 30%, var(--bg-0)); }
.stage .score-hint { margin: 14px 0 0; color: var(--fg-1); font-weight: 650; }
.response-stage { padding-bottom: 12px; }
.outcome-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.response-stage .example-row { margin: 12px 0; padding: 12px 14px; border-radius: var(--r-sm); background: var(--bg-0); }
.example-row code { overflow-wrap: anywhere; }
.response-stage .again { margin-top: 4px; }
.weight-changes { display: flex; flex-wrap: wrap; gap: 10px; margin: 2px 0 16px; }
.weight-changes p { margin: 0; padding: 10px 14px; border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--bg-0); font-variant-numeric: tabular-nums; }
.future-table { min-width: 570px; }
.future-table td { width: auto; }
.future-table td:last-child { color: var(--fg-0); }
.future-table .after-score { font-variant-numeric: tabular-nums; white-space: nowrap; }
.stage .future-result { padding: 12px 14px; border-radius: var(--r-sm); background: var(--bg-1); color: var(--fg-0); font-weight: 650; }
.toy-note { margin: 0; color: var(--fg-3); font-size: 13px; }
@media (max-width: 680px) {
  .controls, .candidates { grid-template-columns: 1fr; }
  .stage h4 { align-items: flex-start; }
}
</style>
