# Bosca Studio: Backend Integration Spec

## Context

bosca-studio is a Nuxt 4 app with a polished custom design system but zero backend connectivity — all data is mock. The goal is to wire it to the real Bosca backend by building a custom GraphQL client (based on `$fetch`), integrating the three `@bosca` client libraries (auth, forms, analytics), and proving the full stack with one real data page.

The old admin app (`bosca-framework/web/administration`) provides proven patterns but uses Apollo + `@nuxt/ui`/Tailwind, neither of which we want. We'll adapt the integration patterns while keeping bosca-studio's design system intact.

---

## Phase 1: Foundation + Proof-of-Concept ✅

### 1.1 — Package Registry & Dependencies ✅

**Created** `.npmrc` (gitignored — contains auth token):
```
shamefully-hoist=true
@bosca:registry=https://artifacts.example.com/npm
//artifacts.example.com/npm/:_authToken=<token>
```

**Added to** `package.json`:
- `@bosca/auth-client-browser`
- `@bosca/forms`
- `@bosca/analytics-client-browser`
- `@graphql-codegen/cli` (devDep)
- `@graphql-codegen/typescript` (devDep)
- `@nuxt/devtools` (devDep — required after `shamefully-hoist=true`)

### 1.2 — Runtime Config & Route Rules ✅

**Modified** `nuxt.config.ts`:
- `runtimeConfig` with `apiUrl` (server-only) and public keys: `apiUrl`, `authDomain`, `analyticsUrl`, `appVersion`, `imageBaseUrl`
- `routeRules` proxying `/graphql`, `/api/v1/**`, `/content/**`, `/oauth2/**` to backend
- `nitro.devProxy` for WebSocket `/graphqlws`
- `vite.ssr.noExternal` for `@bosca/*` packages (extensionless ESM fix)

### 1.3 — Custom GraphQL Client ✅

**Created** `app/composables/useGraphQL.ts`

Built on `$fetch`, provides:
- `query<T>(gql, variables?)` — raw query
- `mutation<T>(gql, variables?)` — raw mutation
- `useAsyncQuery<T>(key, gql, variables?, options?)` — SSR-deduped via `useAsyncData`

Token injection:
- **Client**: `$auth.getAuthHeaders()` via auth SDK
- **Server (SSR)**: `useRequestHeaders(['cookie'])` forwards cookies to backend

### 1.4 — GraphQL Codegen ✅

**Created** `codegen.yml` — schema-only type generation (18,350 lines generated).

### 1.5 — Auth Plugin ✅

**Created** `app/plugins/01.auth.ts` — `setupNuxtAuth`, provides `$auth`.

### 1.6 — Server Auth Middleware ✅

**Created** `server/middleware/auth.ts` — forwards `Cookie` header to profile endpoint for validation. Allows unauthenticated access to `/auth/*`, `/_*`, `/api/*`, `/graphql`, `/health`.

### 1.7 — Client Auth Middleware ✅

**Created** `app/middleware/auth.global.ts` — checks `$auth.isAuthenticated` on client-side navigation.

### 1.8 — Wire Login Page ✅

**Modified** `app/pages/auth/login.vue` — calls `$auth.signInWithPassword()`, redirects on success.

### 1.9 — Forms Plugin ✅

**Created** `app/plugins/02.forms.client.ts` — `setupBoscaForms`, provides `$forms`. API-only (no `BoscaForm` component — avoids `@nuxt/ui` dependency).

### 1.10 — Analytics Plugin ✅

**Created** `app/plugins/03.analytics.client.ts` — `setupNuxtAnalytics`, wires `$auth` user ID.

### 1.11 — Health Route ✅

**Created** `server/routes/health.ts`.

### 1.12 — Proof-of-Concept Page ✅

**Modified** `app/pages/audience/organizations.vue` — wired to real GraphQL search query with pagination, replacing mock data.

---

## Key Decisions

- **`$fetch` over Apollo**: No cache normalization needed (admin tool uses network-first). SSR-deduped via `useAsyncData`.
- **Auth SDK via `$auth`**: Never import `useAuth()` directly (fails during SSR). Always use `useNuxtApp().$auth`.
- **Cookie forwarding for SSR**: Server-side GraphQL requests forward the raw `Cookie` header — backend owns the cookie contract.
- **Forms API-only**: `@bosca/forms` BoscaForm component depends on `@nuxt/ui`. We use the composable/API layer only; form UI built in bosca-studio's design system.
- **`vite.ssr.noExternal`**: Required for `@bosca/*` packages (extensionless ESM imports).

---

## Phase 2: Read-Only Page Migration

Wire mock pages to real GraphQL queries, one subsystem at a time. **Read-only first** — no mutations, no modals, no CRUD. Each page follows the canonical pattern from `audience/organizations.vue`:

```
1. const { useAsyncQuery } = useGraphQL()
2. Define inline GQL query string with typed variables
3. const { data, status } = useAsyncQuery<ResponseType>(key, gql, { query: searchQuery, filter, limit, offset })
4. Derive display data via computed properties
5. Handle loading (status === 'pending') and empty states
6. Wire pagination via offset/limit refs (watched automatically)
```

### Per-page dashboard linkage

Stat tiles, charts, and KPIs on every page are **backend-configured, not hardcoded**. Each page declares a dashboard key; the frontend renders whatever visualizations the backend defines. See **Phase 4** for the full analytics visualization system spec.

Current state: `useDashboardStats.ts` is a placeholder composable that handles `NUMBER`-type visualizations only. It will be replaced by the general-purpose `useDashboard` composable + `<DashboardGrid>` component in Phase 4.

**Interim approach** (used now): `useDashboardStats(dashboardKey)` fetches dashboard, executes queries, returns `StatTile[]`.
**Target approach** (Phase 4): `<DashboardGrid dashboard-key="..." />` renders all visualization types.

### Wiring order

Ordered by similarity to the proven pattern and increasing complexity.

#### 2.1 — Audience: Profiles ✅
- [x] Wire `profiles.vue` to `GetAllProfiles` (Admin Search Index, `contentType = "bosca/v-profile-generic"`)
- [x] Replace 6 hardcoded profiles with search results, pagination, search bar
- [x] Wire total count stat tile from `estimatedHits`; remaining stats show `--` pending analytics queries
- [x] Wire profile detail panel to `GetProfileById` + `GetProfileSegments` (parallel fetch on row click)
- [x] Backend queries used: `GetAllProfiles`, `GetProfileById`, `GetProfileSegments`
- [ ] Wire remaining stat tiles once analytics queries are seeded (Active 30d, VIP via segment, Churn risk)

#### 2.1b — Audience: Organization stat tiles
- [ ] Add stat tiles to `organizations.vue` (Total from `estimatedHits`, others from analytics queries)
- [ ] Wire once analytics queries are seeded (New this week, Total members, Avg. members)

#### 2.2 — CMS: Collections ✅
- [x] Wire `collections.vue` to `GetAllCollections` (Admin Search Index, `_type = "collection"`)
- [x] Replace 8 mock collections with real data, search bar, pagination
- [x] Map workflow state to existing status badge UI, `itemsCount` for item count
- [x] Request `facets: ["type"]` for future type filter support
- [x] Backend queries used: `GetAllCollections`

#### 2.3 — CMS: Documents ✅
- [x] Wire `documents.vue` to `GetAllDocuments` (Admin Search Index, `_type = "metadata"`)
- [x] Replace 10 mock documents with real data, search bar, pagination
- [x] Map workflow state to status pill, content type to human-readable label
- [x] Relative time display from `modified` timestamp
- [x] Backend queries used: `GetAllDocuments`

#### 2.4 — CMS: Metadata (Gallery)
- [ ] Wire `metadata.vue` to `GetAllMetadata` (all content types)
- [ ] Replace 8 gallery items with real data
- [ ] Wire 4 filter dropdowns (Type, Status, Workflow, Locale) to backend facets
- [ ] Wire tab counts (All, Documents, Media, Data, Guides, Bibles)
- [ ] Wire search bar to query variable
- [ ] Backend queries: `GetAllMetadata`, `GetMetadataAllDocumentFacets`

#### 2.5 — CMS: Guides & Bibles
- [ ] Wire `guides.vue` to `GetAllMetadata` filtered by guide content types
- [ ] Wire `bibles.vue` to `GetBibleQuery` / `GetMetadataBible`
- [ ] Backend queries: `GetAllMetadata`, `GetBibleQuery`, `GetMetadataBible`

#### 2.6 — CMS: Calendar
- [ ] Wire `calendar.vue` to `GetCalendarQuery` / `AdminEventsQuery`
- [ ] Map scheduled items to week grid
- [ ] Backend queries: `GetCalendarQuery`, `AdminEventsQuery`, `GetTimeEvents`

#### 2.7 — Forms
- [ ] Wire `forms.vue` to `FormsQuery` / `FormSchemasQuery`
- [ ] Replace 6 mock forms with real data
- [ ] Wire stat tiles (live count, responses, conversion) from query aggregation
- [ ] Backend queries: `FormsQuery`, `FormSchemasQuery`, `SubmissionsQuery`

#### 2.8 — Localization ✅
- [x] Wire `locales.vue` to `GetLocalizationProjects` — projects list with name, source language, target count, modified date
- [x] Replace 8 mock locales with real project data from backend
- [x] Wire completion percentages from `TranslationProgress` per language (project detail page)
- [x] Add `[projectId].vue` detail page with per-language progress bars, status pills, translation breakdown
- [x] Backend queries: `GetLocalizationProjects`, `GetLocalizationProject`, `GetProjectProgress`
- [ ] See `specs/localization.md` for full localization subsystem spec (strings, mutations, settings)

#### 2.9 — Experiments
- [ ] Wire `flags.vue` to `GetAllFeatureFlags` and `GetAllExperiments`
- [ ] Replace 6 mock flags + 3 mock experiments with real data
- [ ] Wire rollout percentages and environment info
- [ ] Backend queries: `GetAllFeatureFlags`, `GetAllExperiments`, `GetFlagsForExperiment`

#### 2.10 — WorkOps: Tasks
- [ ] Wire `tasks/index.vue` to `TasksQuery`
- [ ] Replace 7 mock tasks with real data
- [ ] Wire stat tiles from task counts by status
- [ ] Wire status/priority filters
- [ ] Backend queries: `TasksQuery`, `WORKOPS_LOOKUP` (labels, priorities, statuses)

#### 2.11 — WorkOps: Projects & Others
- [ ] Wire `projects.vue` to `ProjectsQuery`
- [ ] Wire `boards.vue` to `BoardsQuery`
- [ ] Wire `milestones.vue` to `MilestonesQuery`
- [ ] Wire `inbox.vue` to `InboxQuery`
- [ ] Backend queries: `ProjectsQuery`, `BoardsQuery`, `MilestonesQuery`, `InboxQuery`

#### 2.12 — System: Jobs & Infrastructure
- [ ] Wire `jobs.vue` to `JobDefinitionsQuery` + `HealthCheckQuery`
- [ ] Replace mock service cards with real health data
- [ ] Wire job queue with real job states
- [ ] Backend queries: `JobDefinitionsQuery`, `HealthCheckQuery`, `AdminEventsQuery`

#### 2.13 — Analytics: Overview Dashboard
- [ ] Wire `overview.vue` — this is the most complex page
- [ ] Wire HUD components (`HudKpi`, `HudPipelineFlow`, `HudWorkflowStates`, etc.) to real queries
- [ ] Wire activity stream to real events
- [ ] Backend queries: `GetDashboards`, `ExecuteAnalyticsQuery`, `GetAnalyticsQueries`, `HealthCheckQuery`

---

## Phase 3: Auth Pages & Mutations

Once pages display real data, add write operations and complete auth flows.

### 3.1 — Remaining Auth Pages
- [ ] Wire `signup.vue` — `$auth.signUp()` flow
- [ ] Wire `forgot.vue` — `$auth.forgotPassword()` flow
- [ ] Wire `reset-password.vue` — `$auth.resetPassword()` flow
- [ ] Wire `verify.vue` — email verification code
- [ ] Wire `two-factor.vue` — TFA code submission
- [ ] Wire `sso.vue` — SSO redirect via `$auth`
- [ ] Wire `workspaces.vue` — fetch real workspace list from profile
- [ ] Wire `magic-link.vue` — magic link verification
- [ ] Wire `invite.vue` — invitation acceptance

### 3.2 — CRUD Mutations (per subsystem)

#### Audience
- [ ] Create/edit profile (`AddProfileQuery`, `EditProfileQuery`)
- [ ] Segment management (`AddSegmentMember`, `RemoveSegmentMember`)
- [ ] Sync profiles (`SyncProfileMutation`)

#### CMS
- [ ] Create metadata with upload (`AddCollectionUploadMetadata`)
- [ ] Edit metadata (`SaveMetadata`, `RenameMetadataMutation`)
- [ ] Workflow transitions (`BulkPublishMetadata`, `BulkSetMetadataReadyAll`, `setMetadataReady`, `SetNotReadyQuery`)
- [ ] Bulk operations (select-all + Translate/Schedule/Publish/Flag from `metadata.vue` bulk bar)
- [ ] Visibility toggles (`SetPublic`, `SetSearchable`, `SetPublicContent`)
- [ ] Document CRUD (`AddDocumentMutation`, `RemoveDocumentMutation`)
- [ ] Guide steps (`AddGuide`, `AddGuideStep`)
- [ ] Delete (`BulkDeleteMetadata`, `DeleteAllDocumentsMutation`)
- [ ] Lock/unlock (`SetLockedQuery`)

#### Forms
- [ ] Delete submission (`DeleteSubmissionMutation`)

#### Experiments
- [ ] Feature flag CRUD (`AddFeatureFlag`, `EditFlagMutation`, `DeleteFeatureFlag`, `SetFlagStatus`)
- [ ] Experiment CRUD (`AddExperimentMutation`, `EditExperimentMutation`, `DeleteExperimentMutation`)
- [ ] Conversion goals (`AddConversionGoal`, `EditConversionGoal`, `DeleteConversionGoal`)

#### Localization
- [ ] Add/delete locale (`AddLanguageMutation`)
- [ ] Set translations (`SetTranslationMutation`, `SetPluralTranslationMutation`)
- [ ] Add/delete strings (`AddStringMutation`, `DeleteStringMutation`)
- [ ] Project management (`AddLocalizationProject`, `DeleteLocalizationProject`)

#### WorkOps
- [ ] Task CRUD (`CreateMutation`, `UpdateMutation`, `CloseMutation`)
- [ ] Task transitions (`TransitionMutation`, `TransitionPluralMutation`)
- [ ] Time logging (`LogWorkMutation`, `DeleteWorklogMutation`)
- [ ] Board/column management (`CreateBoardMutation`, `UpdateBoardMutation`, `CreateColumnMutation`)
- [ ] Sprint management (`CreateSprintMutation`, `StartSprintMutation`, `CloseSprintMutation`)

#### System
- [ ] Configuration (`EditConfiguration`, `DeleteConfiguration`)
- [ ] Group management (`AddGroupQuery`, `EditGroupQuery`, `DeleteGroupQuery`)
- [ ] Cache operations (`ClearCacheQuery`, `ClearCdnCacheQuery`)
- [ ] Job management (`ClearJobLocksQuery`, `ExpireAllJobsQuery`)

### 3.3 — File Upload Composable
- [ ] Port `useUploader.ts` from old admin app
- [ ] Integrate with `AddCollectionUploadMetadata` (returns signed upload URL)
- [ ] Wire upload progress via `UploadProgress` subscription
- [ ] Used by CMS metadata creation flow

---

## Phase 4: Analytics Visualization System

Port the analytics visualization, dashboard, and query infrastructure from the old admin app. This system powers stat tiles on every page, the analytics overview dashboard, and the admin query/visualization/dashboard editors.

### Source inventory

All source files live in `bosca-framework/web/administration/`. Total: ~4,400 lines across 18 files.

| Layer | Files | Lines | External deps |
|-------|-------|-------|---------------|
| Configuration classes | `app/utils/analytics/` (10 files) | 657 | `chroma-js`, `date-fns` |
| Visualization renderer | `app/components/analytics/AnalyticsVisualization.vue` | 368 | `vue-chrts`, `d3-geo`, `@unovis/ts` |
| Supporting components | `DatePicker.vue`, `AnalyticsDateParameterInput.vue`, `AnalyticsParameterEditor.vue`, `EventDetail.vue` | 568 | `@internationalized/date`, `json-editor-vue` |
| Query editor pages | `settings/analytics/queries/` (2 files) | 473 | `MonacoEditor` |
| Visualization editor pages | `settings/analytics/visualizations/` (2 files) | 841 | — |
| Dashboard editor pages | `settings/analytics/dashboards/` (2 files) | 850 | `gridstack` |
| Events/errors browser | `settings/analytics/events/`, `errors/` (3 files) | 931 | — |

### Porting strategy

Every source file uses `@nuxt/ui` components (UButton, UTable, UInput, USelect, UCard, UFormField, etc.) and Tailwind classes. These must be replaced with bosca-studio's design system (`Btn`, `SectionCard`, `Pill`, `Icon`, scoped CSS). The visualization logic, configuration classes, and GraphQL queries are framework-agnostic and port directly.

Apollo Client calls (`useAsyncQuery`, `useMutation`, `gql`) become `useGraphQL()` calls (`query`, `mutation`, `useAsyncQuery`).

### 4.1 — Chart color system (replaces `makePaletteFromPrimary`)

The old admin app generates chart colors at runtime by interpolating between 4 CSS variables using `chroma-js`. This is fragile (SSR-hostile, reads `getComputedStyle`), produces muddy results, and ties the palette to `@nuxt/ui` variables that don't exist here.

**New approach: design tokens, not computation.**

#### Categorical palette (multi-series charts, pie/doughnut)

Hand-picked colors with even perceptual spacing in oklch, designed for dark backgrounds. Added to `app/assets/css/main.css` as CSS custom properties:

```css
:root {
  --chart-1: oklch(0.75 0.18 220);   /* cyan-blue */
  --chart-2: oklch(0.72 0.19 155);   /* teal-green */
  --chart-3: oklch(0.75 0.16 310);   /* violet */
  --chart-4: oklch(0.78 0.17 55);    /* amber */
  --chart-5: oklch(0.70 0.20 350);   /* rose */
  --chart-6: oklch(0.73 0.15 265);   /* indigo */
  --chart-7: oklch(0.80 0.16 135);   /* lime */
  --chart-8: oklch(0.72 0.18 25);    /* orange */
  --chart-9: oklch(0.68 0.17 290);   /* purple */
  --chart-10: oklch(0.76 0.14 185);  /* aqua */
}

[data-theme="light"] {
  /* Same hues, adjusted lightness/chroma for light backgrounds */
  --chart-1: oklch(0.55 0.20 220);
  /* ... */
}
```

Access in JS:

```typescript
function getCategoricalPalette(count: number): string[] {
  const style = getComputedStyle(document.documentElement)
  return Array.from({ length: Math.min(count, 10) }, (_, i) =>
    style.getPropertyValue(`--chart-${i + 1}`).trim()
  )
}
```

This is only called once per render, not per data point. The colors are designed, not computed.

#### Sequential palette (heatmaps, intensity, single-hue gradients)

Generated from the subsystem accent by varying lightness in oklch. No library needed — pure CSS `oklch()` or a tiny utility:

```typescript
function getSequentialPalette(accent: string, steps: number): string[] {
  // Parse the accent's hue and chroma, generate lightness ramp
  // from 0.35 (dark) to 0.85 (light) in even steps
  // Falls back to brand-2 if accent can't be parsed
}
```

For CSS-only use (e.g., heatmap cells), define as `color-mix()` steps from the accent:

```css
.intensity-1 { background: color-mix(in oklch, var(--accent) 20%, var(--bg-2)); }
.intensity-2 { background: color-mix(in oklch, var(--accent) 40%, var(--bg-2)); }
/* ... */
```

#### What this replaces

- **Eliminates `chroma-js` dependency** — no runtime color interpolation
- **Eliminates `makePaletteFromPrimary()`** — replaced by `getCategoricalPalette()`
- **Eliminates `--ui-primary` / `--ui-graphColor*` references** — replaced by `--chart-*` tokens
- **SSR-safe** — tokens are static CSS, sequential palette can use accent string directly

#### Integration with configuration classes

`BarLineScatterConfiguration` and `PieDoughnutConfiguration` call `makePaletteFromPrimary()` in their `getCategories()` methods. Replace with:
- `getCategoricalPalette(this.yAxisKeys.length)` for bar/line/scatter
- `getCategoricalPalette(this.getData().length)` for pie/doughnut

### 4.2 — Configuration classes

Port `app/utils/analytics/` to `app/utils/analytics/`:

- [ ] `VisualizationConfiguration.ts` — abstract base class. Field settings, data transformation pipeline (type coercion, date parsing with epoch heuristic). **No UI deps — copy directly.**
- [ ] `VisualizationFactory.ts` — factory mapping `AnalyticsVisualizationType` enum to concrete classes. **No UI deps — copy directly.**
- [ ] `BarLineScatterConfiguration.ts` — x/y axis keys, date formatting via `date-fns`. **Replace `makePaletteFromPrimary()` → `getCategoricalPalette()`. Dep: `date-fns`.**
- [ ] `PieDoughnutConfiguration.ts` — label/value keys, donut data transform. **Replace `makePaletteFromPrimary()` → `getCategoricalPalette()`. Dep: `date-fns`.**
- [ ] `NumberConfiguration.ts` — single-value display with format (currency, percent, locale number). **No UI deps.**
- [ ] `TableConfiguration.ts` — column selection/ordering. **No UI deps.**
- [ ] `LabelConfiguration.ts` — text label with font size/weight/alignment. **Replace Tailwind classes (`text-base`, `font-normal`, `text-left`) with design tokens (`--font-size-*`, etc.) or inline values.**
- [ ] `DatePickerConfiguration.ts` — parameter mapping for date range. **No UI deps.**
- [ ] `TopoJsonMapConfiguration.ts` — region/value column mapping, area data transform. **No UI deps.**
- [ ] `colors.ts` — **Rewrite entirely.** Replace `makePaletteFromPrimary()` with `getCategoricalPalette()` and `getSequentialPalette()`. No `chroma-js` dep.

### 4.3 — Dependencies

Add to `package.json`:

```
# Chart rendering
vue-chrts              # BarChart, LineChart, DonutChart, BubbleChart, TopoJSONMap
date-fns               # Date formatting in axis labels and tooltips

# Map visualization (only needed if TopoJSON maps are used)
d3-geo                 # geoMercator projection
@unovis/ts             # WorldMapTopoJSON data

# Dashboard layout
gridstack              # Drag-drop grid for dashboard editor

# Editor tools
json-editor-vue        # JSON configuration editing
monaco-editor          # SQL query editing (or nuxt-monaco-editor)

# Date picker
@internationalized/date  # Calendar date logic
```

**Not needed** (eliminated vs old admin app):
- `chroma-js` — replaced by CSS `oklch()` tokens and `color-mix()`

### 4.4 — Core visualization renderer

Port `AnalyticsVisualization.vue` → `app/components/analytics/Visualization.vue`

This is the central component — given a visualization definition + parameters, it:
1. Executes the linked analytics query via GraphQL
2. Creates a `VisualizationConfiguration` via the factory
3. Transforms query results through the configuration's `setData()` pipeline
4. Renders the appropriate chart component based on `type`

**Rendering by type:**

| Type | Chart component | Configuration class |
|------|----------------|-------------------|
| `BAR` | `<BarChart>` from `vue-chrts` | `BarLineScatterConfiguration` |
| `LINE` | `<LineChart>` from `vue-chrts` | `BarLineScatterConfiguration` |
| `SCATTER` / `BUBBLE` | `<BubbleChart>` from `vue-chrts` | `BarLineScatterConfiguration` |
| `PIE` / `DOUGHNUT` | `<DonutChart>` from `vue-chrts` | `PieDoughnutConfiguration` |
| `NUMBER` | Text display (stat tile) | `NumberConfiguration` |
| `TABLE` | HTML table (replaces `<UTable>`) | `TableConfiguration` |
| `LABEL` | Text label | `LabelConfiguration` |
| `DATEPICKER` | Date range picker | `DatePickerConfiguration` |
| `TOPO_JSON_MAP` | `<TopoJSONMap>` from `vue-chrts` | `TopoJsonMapConfiguration` |

**Porting changes:**
- [ ] Replace `<UButton>` with `<Btn>`
- [ ] Replace `<UIcon>` with `<Icon>`
- [ ] Replace `<UTable>` with HTML `<table>` using existing table styles
- [ ] Replace Tailwind utility classes with scoped CSS
- [ ] Replace Apollo `useAsyncQuery` / `gql` with `useGraphQL().useAsyncQuery()`
- [ ] Replace `useElementSize` (from `@vueuse/core`) — check if already available or add
- [ ] Replace `--ui-*` CSS variables with bosca-studio design tokens (`--brand-*`, `--fg-*`, `--bg-*`, etc.)

### 4.5 — Supporting components

- [ ] `DatePicker.vue` → `app/components/analytics/AnalyticsDatePicker.vue` — Replace `<UPopover>`, `<UCalendar>`, `<UButton>` with bosca-studio equivalents. Replace `@internationalized/date` usage or keep as dep.
- [ ] `AnalyticsDateParameterInput.vue` → `app/components/analytics/AnalyticsDateParameterInput.vue` — Replace `<UInput>`, `<USelect>`. Relatively simple component.
- [ ] `AnalyticsParameterEditor.vue` → `app/components/analytics/AnalyticsParameterEditor.vue` — Replace `<UButton>`, `<UInput>`, `<USelect>`, `<UFormField>`, `<UCard>`, `<UCheckbox>`.
- [ ] `EventDetail.vue` → `app/components/analytics/AnalyticsEventDetail.vue` — Replace `<UBadge>` with `<Pill>`, replace `json-editor-vue` usage. Complex event data viewer.

### 4.6 — `useDashboard` composable

Replace the current `useDashboardStats.ts` with a general-purpose `useDashboard.ts`:

```typescript
export function useDashboard(dashboardKey: string) {
  // 1. Fetch dashboard by key (includes visualizations with their definitions)
  // 2. Resolve dashboard-level parameters (date ranges, filters)
  // 3. Execute all visualization queries in parallel
  // 4. Return reactive state:
  //    - dashboard: the dashboard metadata
  //    - visualizations: array of { instance, config, data, status }
  //    - parameters: reactive dashboard parameters (for date pickers to write to)
  //    - refresh(): re-execute all queries
}
```

- [ ] Fetch via `analytics.dashboards.byKey(key)`
- [ ] For each visualization with a `queryId`, execute `analytics.queries.execute(queryId, parameters)`
- [ ] Merge dashboard parameters with per-visualization parameters
- [ ] Handle parameter changes (date picker emits → re-execute affected queries)
- [ ] Return visualization instances with their data for rendering

### 4.7 — `<DashboardGrid>` component

A drop-in component that renders an entire dashboard given a key:

```vue
<DashboardGrid dashboard-key="audience.profiles" :accent="accent" />
```

- [ ] Uses `useDashboard(key)` internally
- [ ] Renders each visualization via `<AnalyticsVisualization>`
- [ ] Layout from dashboard `configuration` JSON (grid positions, sizes)
- [ ] For read-only display: simple CSS grid based on configuration
- [ ] For editor mode: `gridstack` for drag-drop (dashboard editor page only)
- [ ] Shows loading skeleton while queries execute
- [ ] Gracefully handles missing dashboard (renders nothing)

### 4.8 — Per-page dashboard linkage

Every page that has stat tiles or charts declares a dashboard key. The `<DashboardGrid>` renders whatever the backend defines — no hardcoded tiles.

```vue
<!-- profiles.vue -->
<DashboardGrid dashboard-key="audience.profiles" :accent="accent" />

<!-- organizations.vue -->
<DashboardGrid dashboard-key="audience.organizations" :accent="accent" />

<!-- forms.vue -->
<DashboardGrid dashboard-key="forms.forms" :accent="accent" />

<!-- analytics/overview.vue — the entire page is a dashboard -->
<DashboardGrid dashboard-key="analytics.overview" :accent="accent" />
```

Pages are decoupled from their stats/charts. Adding, removing, reordering, or changing a visualization is a backend-only change.

### 4.9 — Analytics admin pages

Port the query, visualization, and dashboard editors into `app/pages/system/analytics/`:

#### Query editor (`system/analytics/queries/`)
- [ ] `index.vue` — List all analytics queries in a table. CRUD. (148 lines source)
- [ ] `[id].vue` — Query editor with Monaco SQL editor, parameter management, execution preview, results table. (325 lines source)
- [ ] **Key porting**: Replace `<MonacoEditor>` setup, `<UTable>` → HTML table, `<UModal>` → custom modal, `json-editor-vue` for configuration JSON

#### Visualization editor (`system/analytics/visualizations/`)
- [ ] `index.vue` — List all visualizations. CRUD. (155 lines source)
- [ ] `[id].vue` — Visualization editor with type selection, field mapping, live preview via `<AnalyticsVisualization>`, format configuration. (686 lines source — largest file)
- [ ] **Key porting**: Heavy `@nuxt/ui` form component usage (`<UFormField>`, `<USelect>`, `<USelectMenu>`, `<UInput>`, `<UAccordion>`). Type-specific config panels for each visualization type.

#### Dashboard editor (`system/analytics/dashboards/`)
- [ ] `index.vue` — List all dashboards. CRUD. (148 lines source)
- [ ] `[id].vue` — Dashboard editor with GridStack drag-drop, visualization placement, parameter cascading, permission management. (702 lines source)
- [ ] **Key porting**: `gridstack` integration (framework-agnostic, ports directly), permission UI, visualization add/remove/lock

#### Events & errors browser (`system/analytics/events/`, `system/analytics/errors/`)
- [ ] `events/index.vue` — Raw analytics event browser with filters, pagination, event detail slideover. (280 lines source)
- [ ] `errors/index.vue` — Error group list with status/severity filters, pagination. (332 lines source)
- [ ] `errors/[fingerprint].vue` — Error detail with stack trace viewer, status management, AI root cause analysis. (319 lines source)

---

## Phase 5: Real-Time, Codegen & Remaining Features

### 5.1 — WebSocket Subscriptions
- [ ] Create `useGraphQLSubscription` composable over `/graphqlws`
- [ ] Wire `MetadataChangesSub` — live CMS updates (new content, status changes)
- [ ] Wire `UploadProgress` — file upload progress bars
- [ ] Wire activity stream in analytics dashboard

### 5.2 — Operation-Level Codegen
- [ ] Extract inline GQL strings to `.graphql` files (one per operation)
- [ ] Add `@graphql-codegen/typescript-operations` to codegen pipeline
- [ ] Generate typed `DocumentNode` exports + exact response types
- [ ] Update `useAsyncQuery` calls to use generated types (eliminates manual `<ResponseType>` generics)

### 5.3 — Detail / Editor Pages
- [ ] CMS metadata detail page — `MetadataQuery`, document editor, supplementary files
- [ ] CMS collection detail page — `CollectionQuery`, item management
- [ ] WorkOps task detail page — `TaskQuery`, comments, worklogs, transitions
- [ ] WorkOps project detail page — `ProjectQuery`, task list, milestones
- [ ] Experiment detail — `GetFlagsForExperiment`, arm configuration, results

### 5.4 — System Administration
- [ ] Storage system management (`AddStorageSystem`, `EditStorageSystem`)
- [ ] Search index management (`CreateIndexMutation`, `ReindexQuery`)
- [ ] Database stats views (`TableStatsQuery`, `SlowQueriesQuery`, `UnusedIndexesQuery`)
- [ ] Workflow state/transition editor (`AddState`, `EditState`, `AddTransition`)
- [ ] Model/type management (`AddModelMutation`, `AddTypeMutation`)
- [ ] Agent/tool/script management (CRUD for agents, tools, scripts, prompts, MCP servers)
- [ ] Backup/restore (`CreateBackupMutation`, `RestoreBackupMutation`)

---

## GraphQL Operation Reference

Summary of backend operations available, organized by domain. Full definitions live in `bosca-framework/web/administration`.

### Profiles & Audience
- **Queries**: `SearchProfiles`, `PreviewProfile`, `GetCurrentProfile`, `FindProfileGroups`, `GetStaticSegments`, `GetProfileSegments`
- **Mutations**: `AddProfileQuery`, `EditProfileQuery`, `AddSegmentMember`, `RemoveSegmentMember`, `SyncProfileMutation`

### Content (Collections + Metadata)
- **Queries**: `GetAllCollections`, `GetAllMetadata`, `CollectionQuery`, `MetadataQuery`, `PreviewMetadataQuery`, `GetMetadataAllDocumentFacets`, `GetCollectionAllDocumentFacets`
- **Mutations**: `AddCollectionUploadMetadata`, `SaveMetadata`, `RenameMetadataMutation`, `BulkDeleteMetadata`, `BulkPublishMetadata`, `BulkSetMetadataReadyAll`, `BulkSetPublicMetadata`, `SetPublic`, `SetSearchable`, `SetLockedQuery`
- **Subscriptions**: `MetadataChangesSub`, `UploadProgress`

### Documents, Guides & Bibles
- **Queries**: `GetMetadataDocument`, `GetMetadataGuide`, `GetMetadataGuideStep`, `DocumentQuery`, `GetBibleQuery`, `GetMetadataBible`, `GetMetadataBibleChapter`, `FindBibleReference`
- **Mutations**: `AddDocumentMutation`, `RemoveDocumentMutation`, `AddGuide`, `AddGuideStep`, `SetDocumentTemplate`, `AddLanguageVariant`

### Forms
- **Queries**: `FormsQuery`, `FormSchemasQuery`, `SubmissionsQuery`
- **Mutations**: `DeleteSubmissionMutation`

### Experiments
- **Queries**: `GetAllExperiments`, `GetAllFeatureFlags`, `GetFlagsForExperiment`, `ExposuresQuery`
- **Mutations**: `AddExperimentMutation`, `EditExperimentMutation`, `DeleteExperimentMutation`, `AddFeatureFlag`, `EditFlagMutation`, `DeleteFeatureFlag`, `SetFlagStatus`, `AddConversionGoal`, `EditConversionGoal`, `DeleteConversionGoal`

### Analytics
- **Queries**: `GetAnalyticsQueries`, `GetAnalyticsQuery`, `ExecuteAnalyticsQuery`, `GetDashboards`, `GetDashboard`, `GetVisualizations`
- **Mutations**: `AddAnalyticsQuery`, `EditAnalyticsQuery`, `DeleteQueryMutation`, `AddDashboard`, `EditDashboard`, `DeleteDashboardMutation`, `AddDashboardAnalyticsVisualization`, `EditAnalyticsVisualization`

### Localization
- **Queries**: `GetLocalizationProjects`, `TranslationQuery`, `PluralTranslationsQuery`
- **Mutations**: `AddLocalizationProject`, `DeleteLocalizationProject`, `AddLanguageMutation`, `SetTranslationMutation`, `SetPluralTranslationMutation`, `AddStringMutation`, `DeleteStringMutation`

### WorkOps
- **Queries**: `TasksQuery`, `TaskQuery`, `ProjectsQuery`, `ProjectQuery`, `BoardsQuery`, `BoardQuery`, `InboxQuery`, `MilestonesQuery`, `SprintsQuery`, `FiltersQuery`, `LabelsQuery`, `WORKOPS_LOOKUP`
- **Mutations**: `CreateMutation`, `UpdateMutation`, `CloseMutation`, `TransitionMutation`, `LogWorkMutation`, `CreateBoardMutation`, `UpdateBoardMutation`, `CreateSprintMutation`, `StartSprintMutation`, `CloseSprintMutation`, `CreateColumnMutation`

### System
- **Queries**: `GetConfigurations`, `SettingsQuery`, `GroupsQuery`, `GroupQuery`, `HealthCheckQuery`, `JobDefinitionsQuery`, `StorageSystemsQuery`, `IndexSettingsQuery`, `TableStatsQuery`, `SlowQueriesQuery`
- **Mutations**: `EditConfiguration`, `AddGroupQuery`, `EditGroupQuery`, `DeleteGroupQuery`, `ClearCacheQuery`, `ClearCdnCacheQuery`, `ClearJobLocksQuery`

### Calendar
- **Queries**: `GetCalendarQuery`, `CalendarsQuery`, `AdminEventsQuery`, `GetTimeEvents`
- **Mutations**: `AddTimeEvent`, `EditTimeEvent`, `DeleteTimeEvent`, `AddCalendarMutation`

### Models & Types
- **Queries**: `GetModels`, `GetModel`, `GetAllTypes`, `GetAttributeTypes`
- **Mutations**: `AddModelMutation`, `EditModel`, `DeleteModelMutation`, `AddTypeMutation`, `EditTypeMutation`

### States & Workflows
- **Queries**: `GetStates`, `GetState`, `TransitionQuery`
- **Mutations**: `AddState`, `EditState`, `DeleteState`, `AddTransition`, `EditTransition`

### Agents, Scripts & MCP
- **Queries**: `GetAgent`, `GetTools`, `GetScript`, `GetPrompt`, `GetMcpServer`
- **Mutations**: CRUD for each (Add/Edit/Delete patterns)
