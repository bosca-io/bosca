<script setup lang="ts">
/**
 * Guide for the recommendations subsystem: explains candidate generation, read-time personalization,
 * eligibility and language selection, strategy operations, and the serving surfaces. Static reference content
 * — no queries — so it always loads even before any strategy exists.
 */
definePageMeta({ middleware: 'recommendations-admin' })

const { accent } = useCurrentSubsystem()
const router = useRouter()

interface StrategyRow { name: string; ml: boolean; engagement: boolean; what: string }
const strategyTypes: StrategyRow[] = [
  { name: 'Trending', ml: false, engagement: true, what: 'Global velocity — what is gaining engagement right now. A scheduled SQL query over recent events, materialized into a shared pool.' },
  { name: 'People also viewed', ml: false, engagement: true, what: 'Item-to-item behavioral co-engagement ("people who engaged with X also engaged with Z"). A scheduled SQL query, materialized as item→item edges.' },
  { name: 'People like you', ml: false, engagement: true, what: 'Co-engagement conditioned independently on each of the viewer\'s cohort memberships ("people sharing this value — e.g. age band or an interest — who engaged with X also engaged with Z"). A scheduled SQL query over per-membership co-occurrence, materialized as (membership, item)→item edges.' },
  { name: 'Personalized', ml: true, engagement: true, what: 'A two-tower model that retrieves and ranks for the viewer at request time. The default feed is cached briefly by profile, context, and language. Training is a separate offline job; there is no candidate pool to materialize by evaluating this strategy.' },
]

interface SurfaceRow { field: string; label: string; ml: string; engagement: string; personalized: string; what: string }
const surfaces: SurfaceRow[] = [
  { field: 'similar', label: 'Similar', ml: 'Required', engagement: 'No', personalized: 'No', what: '"More like this" from the served content model. Semantic embeddings are training input; they are not queried as a serving fallback. Available after a content model has been trained and loaded.' },
  { field: 'coEngaged', label: 'People also viewed', ml: 'Optional rank', engagement: 'Yes', personalized: 'Optional', what: 'Behavioral item-to-item edges. For a viewer, dismissals are removed and ML re-ranking is attempted; ratings provide the heuristic order when ML is bypassed or unavailable.' },
  { field: 'recommended', label: 'Recommended', ml: 'Content + rank', engagement: 'Optional', personalized: 'Optional', what: 'Merges co-engaged, content-similar, cohort, and learned-neighbor candidates when each is available. A viewer adds dismissal filtering and, when enabled and serving, ML re-ranking.' },
  { field: 'trending', label: 'Trending', ml: 'No', engagement: 'Yes', personalized: 'No', what: 'The materialized global trending pool, filtered by the requested context and language.' },
  { field: 'forYou', label: 'Personalized feed', ml: 'Preferred', engagement: 'Usually', personalized: 'Yes', what: 'Live ML retrieve → rank, cached for five minutes on the default ML arm. If it yields no eligible candidates, the fallback is content-similar seeded by ratings, then trending.' },
  { field: 'placement', label: 'Placement', ml: 'Configurable', engagement: 'Depends', personalized: 'Optional', what: 'A named slot combining linked strategies. Anonymous requests skip Personalized sources; viewer requests filter dismissals and apply rating-aware ranking, freshness, diversity, and any configured care floor.' },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Recommendations', 'Guide')"
        title="How recommendations work"
        subtitle="Strategy types, serving surfaces, and how the behavioral and content signals complement each other">
        <template #actions>
          <Button
            icon="filter"
            size="sm"
            :accent="accent"
            @click="router.push('/recommendations/contexts')">Contexts</Button>
          <Button
            icon="layers"
            size="sm"
            :accent="accent"
            @click="router.push('/recommendations/signals')">Signals</Button>
          <Button
            icon="sliders"
            size="sm"
            :accent="accent"
            @click="router.push('/recommendations/strategies')">Strategies</Button>
          <Button
            icon="play"
            size="sm"
            primary
            :accent="accent"
            @click="router.push('/recommendations/test')">Test feeds</Button>
        </template>
      </PageHeader>
    </template>

    <!-- Overview -->
    <SectionCard title="The shape of it" subtitle="Candidate generation → personalization → serving" padded>
      <div class="prose">
        <p>
          Recommendations start with <strong>candidate generation</strong>: scheduled strategies materialize
          shared or cohort-scoped pools, while the content and Personalized models serve candidates live.
          Profile-aware surfaces then personalize at read time by removing dismissals and, depending on the
          surface, using the learned ranker or the viewer’s ratings. Similar and Trending stay unpersonalized.
        </p>
        <p>
          Strategies do not materialize a complete feed for every profile. Ratings, dismissals, attributes,
          and cached attribute signals are still stored profile data, and the default <code>forYou</code> ML
          result is cached for five minutes by profile, context, and language. Dismissal changes invalidate that
          profile’s feed cache.
        </p>
        <p>
          Two broad sources explain most item candidates, and the system is easier to reason about once you
          separate them:
        </p>
        <ul>
          <li><strong>Behavioral</strong> — learned from what people <em>do</em> (co-occurrence, trending). Powerful, but needs traffic to exist.</li>
          <li><strong>Content</strong> — learned from what things <em>are</em> (type, language, categories,
            labels, and optional semantic embeddings). It can work without audience engagement once the content
            model is trained and serving.</li>
        </ul>
        <p>
          That split is why there are two "item-to-item" answers — <strong>People also viewed</strong> (behavioral) and
          <strong>Similar</strong> (content) — and why the cold-start experience leans on the content side.
          Personalization Signals describe the viewer and can feed either the user model or cohort membership.
        </p>
      </div>
    </SectionCard>

    <!-- Recommendation contexts -->
    <SectionCard title="Recommendation contexts" subtitle="Controlling which content can be returned" padded>
      <div class="prose">
        <p>
          A context is a named candidate set selected by recommendation requests. The <code>default</code>
          context is designed for site and app experiences, so it excludes raw image, video, audio, font,
          model, and binary metadata while allowing other metadata and all collection types. The default
          context cannot be deleted, and its request-facing type cannot be changed.
        </p>
        <p>
          Create additional contexts for focused experiences such as an image picker or video browser. Each
          context can match metadata by content-type prefix and <code>attributes.type</code>, and collections by
          collection type and <code>attributes.type</code>. Include-lists define a complete allow-list; when an
          include-list is empty, its exclude-list is applied instead. Disabling the collection filter excludes
          every collection from that context.
        </p>
        <p class="note">
          After saving or deleting a context, use Recompute assignments on the Contexts list or a saved
          context's page to update metadata and collection assignments across all contexts. This queues
          reclassification using saved eligibility rules. Model training is a separate action: use Train model
          on a context to train with its saved settings. Newly eligible content reaches model-generated
          candidates after a newly trained model becomes active.
        </p>
      </div>
    </SectionCard>

    <!-- Eligibility and language -->
    <SectionCard title="Eligibility and language" subtitle="The filters applied to every candidate" padded>
      <div class="prose">
        <p>
          Every serving surface accepts <code>contextType</code> and <code>languageTag</code>. Omitting the
          context uses <code>default</code>; an unknown context fails clearly instead of silently falling back.
          Before a result is returned, Bosca checks that the content is recommendable, belongs to the selected
          context, and matches the resolved language.
        </p>
        <p>
          When language is omitted, <code>forYou</code>, <code>placement</code>, <code>coEngaged</code>, and
          <code>recommended</code> use the viewer’s profile locale when a viewer is resolved. Anonymous calls,
          <code>similar</code>, and <code>trending</code> use the Recommendations language-resolution fallback,
          installed as English. Pass a BCP 47 tag explicitly when the experience needs a particular language.
        </p>
        <p>
          Language mappings reconcile tags such as <code>en-US</code> with a model/candidate language facet.
          For collections, if an exact requested representation exists, it is the only representation
          considered and must be recommendable. When there is no exact representation, Bosca can select a
          mapped or fallback representation and exposes the chosen tag as <code>collectionLanguageTag</code>.
          Manage mappings under
          <NuxtLink to="/localization/language-mappings">Localization → Language Mappings</NuxtLink>.
        </p>
      </div>
    </SectionCard>

    <!-- Personalization Signals -->
    <SectionCard title="Personalization Signals" subtitle="Configuring what personalizes recommendations" padded>
      <div class="prose">
        <p>
          A <strong>Personalization Signal</strong> is a named, typed value used by the model or by “people like
          you” cohorts. For an attribute source, its <strong>JSONata expression</strong> derives values such as
          <code>age_band</code> from the stored attribute. Supported value types are categorical,
          multi-categorical, numeric, and boolean.
        </p>
        <p>
          Attribute expressions also own the <strong>inclusion conditions</strong>. They receive
          <code>attributes</code>, <code>confidence</code>, <code>verified</code>, <code>source</code>,
          <code>visibility</code>, <code>priority</code>, and <code>typeId</code>, so the expression can decide
          whether a value is trustworthy enough to count.
        </p>
        <ul>
          <li><code>confidence >= 80 ? attributes.band : undefined</code> — only include a high-confidence value; a
            <code>undefined</code> (or absent) result excludes the signal entirely.</li>
          <li>That same gate is how <strong>learned</strong> attributes flow in: attributes inferred from behavior
            (e.g. an interest from what a viewer rates highly) are written with a confidence below certainty, and
            only surface as signals once they clear the threshold your expression sets.</li>
        </ul>
        <p>Each signal may take one or both of two independent roles:</p>
        <ul>
          <li><strong>Model feature</strong> — feeds the Personalized model's user tower, so it learns from age,
            interests, and the like (and can place a brand-new viewer from their declared attributes, before any
            history).</li>
          <li><strong>Cohort membership</strong> — each distinct attribute-signal value defines an independent
            “people like you” group. Cohort signals must be categorical or boolean. Materialization keeps at
            most 64 values per profile and signal key and ignores values longer than 256 characters.</li>
        </ul>
        <p>
          Segment sources behave differently: model training emits membership as the boolean value
          <code>true</code>; it does not evaluate the JSONata expression against a segment. Configure a segment
          signal as boolean and use it as a model feature. The definition still requires a parseable expression,
          but the current cohort materializer reads cached <em>attribute</em> signals, so use an attribute source
          for cohort membership.
        </p>
        <p class="note">
          Attribute values are computed when an attribute is added, updated, or verified and cached on that
          attribute. Creating, editing, or deleting a definition queues a backfill for affected attributes. No
          JSONata expression runs on the recommendation request path; segment membership is read when
          model-training data is assembled.
        </p>
      </div>
    </SectionCard>

    <!-- Strategy types -->
    <SectionCard title="Strategy types" subtitle="The kinds you can create" padded>
      <div class="prose">
        <p>
          A strategy is a recipe for producing candidates. Each type maps to a distinct source of signal.
          Trending, People also viewed, and People like you each run a scheduled analytics query and
          materialize a pool; the Personalized model serves live.
        </p>
      </div>
      <div class="cards">
        <div v-for="s in strategyTypes" :key="s.name" class="typecard">
          <div class="typecard-head">
            <span class="typecard-name">{{ s.name }}</span>
            <span class="chip" :class="s.ml ? 'chip-ml' : 'chip-plain'">{{ s.ml ? 'ML' : 'Rules / SQL' }}</span>
          </div>
          <p class="typecard-what">{{ s.what }}</p>
          <div class="typecard-foot">
            <span class="foot-item">
              <Icon :name="s.engagement ? 'activity' : 'check'" :size="12" />
              {{ s.engagement ? 'Needs engagement data' : 'Works without engagement' }}
            </span>
          </div>
        </div>
      </div>
      <div class="prose note">
        <p>
          Trending, People also viewed, and People like you bind to analytics queries and are refreshed by
          their schedules or <strong>Evaluate now</strong>. Personalized has no analytics query or evaluation
          pool: its strategy evaluation is a no-op. Train it with the daily <code>train-model</code> job or from
          <NuxtLink to="/recommendations/model">Recommendations → ML Model</NuxtLink>.
        </p>
      </div>
    </SectionCard>

    <!-- Serving surfaces -->
    <SectionCard title="Serving surfaces" subtitle="What each API surface returns" padded>
      <div class="table-scroll">
        <table class="surf">
          <thead>
            <tr><th>Surface</th><th>ML</th><th>Needs engagement</th><th>Personalized</th><th>What it returns</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in surfaces" :key="r.field">
              <td><span class="surf-label">{{ r.label }}</span><code class="surf-field">{{ r.field }}</code></td>
              <td><span class="mini" :class="r.ml === 'No' ? 'mini-off' : 'mini-on'">{{ r.ml }}</span></td>
              <td><span class="mini" :class="r.engagement === 'No' ? 'mini-on' : 'mini-off'">{{ r.engagement }}</span></td>
              <td>{{ r.personalized }}</td>
              <td class="surf-what">{{ r.what }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </SectionCard>

    <!-- Related vs Similar -->
    <SectionCard title="People also viewed vs Similar — when to use which" padded>
      <div class="split">
        <div class="split-col">
          <h4><Icon name="activity" :size="14" /> People also viewed <span class="split-sub">behavioral</span></h4>
          <p>"People who engaged with this also engaged with…" Discovery driven by <em>what audiences actually do</em>. Surfaces non-obvious pairings a content model would never guess.</p>
          <p class="split-caveat">Needs traffic. Returns nothing until this item has engagement, and cold items stay empty the longest.</p>
          <p class="split-use"><strong>Use for:</strong> "you might also like," cross-sell, letting the audience's behavior connect items.</p>
        </div>
        <div class="split-col">
          <h4><Icon name="sparkles" :size="14" /> Similar <span class="split-sub">content</span></h4>
          <p>"More like this" by the content itself — returned by the trained content model. Semantic embedding
            chunks feed model training; the serving path does not query them directly as a fallback.</p>
          <p class="split-caveat">Doesn’t know what’s popular or what pairs well behaviorally — only what <em>resembles</em> the source.</p>
          <p class="split-use"><strong>Use for:</strong> "more like this," including items with no engagement,
            after a content model has been trained and loaded.</p>
        </div>
      </div>
      <div class="prose note">
        <p>
          They cover each other’s blind spots: Similar carries the cold start; People also viewed gets sharper as
          traffic grows. That’s exactly why <strong>Recommended</strong> merges them.
        </p>
      </div>
    </SectionCard>

    <!-- People like you -->
    <SectionCard title="'People like you' — two item-context contributions" padded>
      <div class="prose">
        <p>
          Alongside whole-crowd <strong>People also viewed</strong> and content <strong>Similar</strong>,
          <strong>Recommended</strong> can add two ways of finding candidates through people like the viewer.
          Each is optional: when cohort edges or a serving Personalized model are unavailable, the other
          co-engagement and content-similarity sources remain.
        </p>
      </div>
      <div class="split">
        <div class="split-col">
          <h4><Icon name="layers" :size="14" /> Cohort <span class="split-sub">discrete</span></h4>
          <p>"People sharing one of your memberships who engaged with this also engaged with…" — every distinct
            value from a <strong>cohort</strong> signal is an independent membership. Age band and gender do not
            collapse into one combined cohort, and several learned interests remain several memberships. The
            candidates from all of them are merged for the viewer.</p>
          <p class="split-caveat">
            Broad memberships carry broad behavior; a sparse membership contributes no edges until enough shared traffic exists.
          </p>
        </div>
        <div class="split-col">
          <h4><Icon name="activity" :size="14" /> Learned neighbors <span class="split-sub">dense</span></h4>
          <p>The Personalized model finds the viewer’s nearest users by learned embedding, then aggregates
            those neighbors’ <em>model-recommended candidates</em>, weighted by neighbor similarity. This is a
            model-based proxy for taste, not a live query of the neighbors’ raw engagement.</p>
          <p class="split-caveat">Needs the trained model and interaction volume — weak until both accrue.</p>
        </div>
      </div>
      <div class="prose note">
        <p>
          The distinction in one line: <strong>Similar</strong> is about the <em>content</em>, <strong>People
            also viewed</strong> is about <em>everyone’s</em> behavior, and <strong>People like you</strong> is about
          either cohort-conditioned behavior or learned neighboring tastes — different lenses on the same item,
          blended in <strong>Recommended</strong>.
        </p>
      </div>
    </SectionCard>

    <!-- Combining -->
    <SectionCard title="One call that blends them: Recommended" padded>
      <div class="prose">
        <p>
          <code>recommended(metadataId)</code> is the item-context surface that can combine every available
          signal. It:
        </p>
        <ol>
          <li>pulls <strong>People also viewed</strong> (whole-crowd co-engagement), <strong>Similar</strong>
            (content-model similarity), and — for a signed-in viewer — <strong>People like you</strong> (their
            cohort memberships' co-engagement plus their learned neighbors),</li>
          <li>normalizes the scores, merges, and de-duplicates (keeping the stronger signal per item), then</li>
          <li>for a signed-in viewer, drops dismissals and, when the ML engine and model are available,
            <strong>re-ranks with the learned ranker</strong>. Otherwise it keeps the merged base order.</li>
        </ol>
        <p>
          So it behaves well across the whole lifecycle: content-similar when the item is new, increasingly
          behavioral as engagement accrues, and tilted to the individual once they’re signed in. On the CMS
          metadata page, the <strong>Recommended</strong> tab is this call and the <strong>People also viewed</strong>
          tab contains only behavioral candidates. Both requests default to the signed-in Studio user’s primary
          profile when one exists, so their ordering and dismissal filtering can still be personalized.
        </p>
        <p class="note">
          Two related surfaces round it out: <strong>Placement</strong> combines linked strategies into a named
          slot, capped by the placement’s maximum and personalized for a resolved viewer with dismissals,
          ratings, freshness, category diversity, and an optional <code>careFloor</code>. <strong>Personalized
            feed</strong> (<code>forYou</code>) is the whole-catalog per-user feed, not anchored to a single item.
        </p>
      </div>
    </SectionCard>

    <!-- Cold start -->
    <SectionCard title="What works before you have engagement" subtitle="The cold-start ladder" padded>
      <div class="prose">
        <p>With little or no engagement data, behavioral surfaces are empty by definition. In that state:</p>
        <ul>
          <li>The content model can train without interaction data from content type, language, categories,
            labels, and optional semantic embeddings. It still needs a completed training/load cycle. Once it is
            serving, <strong>Similar</strong> and the content part of <strong>Recommended</strong> can return
            items without audience traffic.</li>
          <li><strong>People also viewed</strong> and <strong>Trending</strong> stay empty until traffic accrues — binding a query doesn’t change that; the query simply has no rows to return yet.</li>
          <li>The <strong>Personalized feed</strong> first attempts ML retrieval. If it has no eligible result, it
            tries content-similar candidates seeded by metadata the viewer has rated, then Trending. These
            read-time substitutes have <code>fallback: true</code>.</li>
          <li>A viewer whose configured model features are known may receive personalized retrieval before they
            have their own interaction history, provided the Personalized model has learned and serves those
            feature values.</li>
        </ul>
        <p>
          For an item page with a trained content model but little traffic, lead with
          <strong>Recommended</strong> (or <strong>Similar</strong>) and let <strong>People also viewed</strong>
          contribute as audience behavior accumulates. Any surface can still be empty when none of its sources
          has an eligible candidate.
        </p>
      </div>
    </SectionCard>

    <!-- Operations -->
    <SectionCard title="Operate and verify" subtitle="Use the right action for the result you need" padded>
      <div class="prose">
        <ul>
          <li><NuxtLink to="/recommendations/strategies">Strategies</NuxtLink> — configure schedules and use
            <strong>Evaluate now</strong> to rebuild Trending, People also viewed, or People like you pools.</li>
          <li><NuxtLink to="/recommendations/model">ML Model</NuxtLink> — backfill missing semantic data,
            start model training, and inspect stored and serving model versions. A semantic-data backfill starts
            training afterward only when embeddings changed.</li>
          <li><NuxtLink to="/recommendations/test">Test Console</NuxtLink> — run each live serving surface. A
            result marked <strong>Fallback</strong> came from the <code>forYou</code> cold-start chain.</li>
          <li><NuxtLink to="/recommendations">Overview</NuxtLink> — provision the ML-versus-heuristic or
            model-version A/B test scaffolds. They are created paused for review in Experiments.</li>
        </ul>
      </div>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.prose { font-size: 13.5px; color: var(--fg-2); line-height: 1.6; max-width: 72ch; }
.prose p { margin: 0 0 10px; }
.prose ul, .prose ol { margin: 0 0 10px; padding-left: 20px; }
.prose li { margin: 0 0 5px; }
.prose strong { color: var(--fg-1); font-weight: 600; }
.prose code { font-family: var(--font-mono, monospace); font-size: 12px; background: var(--bg-3); padding: 1px 5px; border-radius: var(--r-xs); color: var(--fg-1); }
.prose a { color: var(--accent, var(--fg-1)); text-decoration: none; }
.prose a:hover { text-decoration: underline; }
.prose.note { margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--bg-3); color: var(--fg-3); font-size: 12.5px; }

.cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 12px; margin: 14px 0 4px; }
.typecard { border: 1px solid var(--bg-3); border-radius: var(--r-sm); padding: 12px 14px; background: var(--bg-2); }
.typecard-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 6px; }
.typecard-name { font-size: 13.5px; font-weight: 600; color: var(--fg-1); }
.typecard-what { font-size: 12.5px; color: var(--fg-3); line-height: 1.5; margin: 0 0 10px; }
.typecard-foot { font-size: 11.5px; color: var(--fg-4); }
.foot-item { display: inline-flex; align-items: center; gap: 5px; }
.chip { font-size: 10.5px; font-weight: 600; padding: 2px 7px; border-radius: 999px; white-space: nowrap; }
.chip-ml { background: color-mix(in srgb, var(--accent, #7c8cff) 22%, transparent); color: var(--fg-1); }
.chip-plain { background: var(--bg-3); color: var(--fg-3); }

.table-scroll { overflow-x: auto; }
.surf { width: 100%; border-collapse: collapse; font-size: 12.5px; min-width: 620px; }
.surf th { text-align: left; font-weight: 600; color: var(--fg-3); font-size: 11px; text-transform: uppercase; letter-spacing: 0.03em; padding: 0 12px 8px; border-bottom: 1px solid var(--bg-3); }
.surf td { padding: 10px 12px; border-bottom: 1px solid var(--bg-3); color: var(--fg-2); vertical-align: top; }
.surf-label { display: block; font-weight: 600; color: var(--fg-1); }
.surf-field { font-family: var(--font-mono, monospace); font-size: 11px; color: var(--fg-4); }
.surf-what { color: var(--fg-3); line-height: 1.5; }
.mini { font-size: 11px; font-weight: 600; padding: 1px 7px; border-radius: 999px; white-space: nowrap; }
.mini-on { background: color-mix(in srgb, #3fb968 24%, transparent); color: var(--fg-1); }
.mini-off { background: var(--bg-3); color: var(--fg-4); }

.split { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 18px; }
.split-col h4 { display: flex; align-items: center; gap: 7px; font-size: 14px; color: var(--fg-1); margin: 0 0 8px; }
.split-sub { font-size: 11px; font-weight: 500; color: var(--fg-4); text-transform: uppercase; letter-spacing: 0.04em; }
.split-col p { font-size: 12.5px; color: var(--fg-2); line-height: 1.55; margin: 0 0 8px; }
.split-caveat { color: var(--fg-4) !important; }
.split-use { color: var(--fg-2); }
.split-col em { color: var(--fg-1); font-style: italic; }
</style>
