# Localization Subsystem Spec

Extracted from `studio.md` §2.8, §3.2 (Localization), and GraphQL schema. This spec covers wiring the localization subsystem to the real Bosca backend.

## Data Model

The backend's localization system is **project-centric**:

- **Project** — a logical grouping of translatable strings and documents for a single app or context. Has a source language, target languages, export formats, sync bindings, and permissions.
- **String** — a single translatable key within a project. Has a stable `key`, optional context, tags, plural flag, and placeholders. Non-plural strings have one `LocalizationTranslation` per language; plural strings have `LocalizationPluralTranslation` rows per CLDR category per language.
- **Translation** — the translated text for a string+language pair. Follows a workflow state machine: `DRAFT → IN_REVIEW → APPROVED → PUBLISHED → ARCHIVED` (with `AI_GENERATED` and `REJECTED` branches).
- **Document** — a metadata item linked to a project for document-level translation (HTML round-tripping).
- **TranslationProgress** — aggregate counters for a project+language pair: `totalStrings`, `translatedStrings`, `approvedStrings`, `publishedStrings`, `aiGeneratedStrings`, `humanTranslatedStrings`, `percentage`.

### Translation State Machine

```
DRAFT ──────────► IN_REVIEW ──────► APPROVED ──────► PUBLISHED ──────► ARCHIVED
                     │                                                     │
AI_GENERATED ────────┘                                                     │
                     ▲                                                     │
REJECTED ────────────┘◄──── IN_REVIEW                   DRAFT ◄───────────┘
```

---

## Phase 1: Read-Only Wiring (from studio.md §2.8)

### 1.1 — Locales Page (`locales.vue`) ✅

Wire `app/pages/localization/locales.vue` to show system locales and projects.

**GraphQL queries:**
```graphql
query GetLanguagesList {
  languages {
    all { tag, name, localName }
  }
}

query GetLocalizationProjects {
  localization {
    projects {
      id, name, description, sourceLanguage
      languages { languageTag }
    }
  }
}
```

**Implementation:**
- [x] Replace 8 mock locales with real language data from `languages.all`
- [x] Show language tag badge, English name, local name, and linked projects
- [x] "Add Locale" button opens modal → `languages.add` mutation
- [x] Projects section below locales, clicking navigates to project detail
- [x] Handle loading and empty states

### 1.2 — Project Detail (`[projectId].vue`) ✅

New page showing per-language translation progress within a project, plus the strings table.

**GraphQL queries:**
```graphql
query GetLocalizationProject($id: UUID!) {
  localization {
    project(id: $id) {
      id
      name
      description
      sourceLanguage
      languages {
        languageTag
        created
      }
    }
  }
}
```

Per-language progress (fetched for each target language):
```graphql
query GetProjectProgress($id: UUID!, $languageTag: String!) {
  localization {
    project(id: $id) {
      progress(languageTag: $languageTag) {
        totalStrings
        translatedStrings
        approvedStrings
        publishedStrings
        aiGeneratedStrings
        humanTranslatedStrings
        percentage
      }
    }
  }
}
```

**Implementation:**
- [x] Show project header with name, source language, description
- [x] Per-language progress bars (percentage, translated/total counts)
- [x] Status derived from percentage thresholds
- [x] Color-coded progress bars matching existing design language
- [x] Back navigation to projects list

### 1.3 — Project Strings (`[projectId]/strings.vue`) — Future

Paginated list of strings within a project, with search and filtering.

**GraphQL query:**
```graphql
query GetProjectStrings($id: UUID!, $offset: Int!, $limit: Int!) {
  localization {
    project(id: $id) {
      id
      name
      sourceLanguage
      strings(offset: $offset, limit: $limit) {
        id
        key
        context
        tags
        plural
        modified
      }
    }
  }
}
```

**Implementation:**
- [ ] Table with key, context, plural flag, tags, modified date
- [ ] Pagination
- [ ] Search/filter by key
- [ ] Click navigates to string detail

---

## Phase 2: Mutations (from studio.md §3.2)

### 2.1 — Project CRUD
- [ ] Create project (`addProject` mutation with `LocalizationProjectInput`)
- [ ] Edit project (`editProject` mutation)
- [ ] Delete project (`deleteProject` mutation)

### 2.2 — String CRUD
- [ ] Add string (`addString` mutation with `LocalizationStringInput`)
- [ ] Edit string (`editString` mutation)
- [ ] Delete string (`deleteString` mutation)

### 2.3 — Translation Management
- [ ] Set translation (`setTranslation` mutation with `LocalizationTranslationInput`)
- [ ] Set plural translation (`setPluralTranslation` mutation)
- [ ] Transition translation state (`transitionTranslation` mutation)
- [ ] Transition plural translation state (`transitionPluralTranslation` mutation)
- [ ] Bulk transition (`bulkTransition` mutation — batch state changes by fromState/toState)

### 2.4 — Project Settings
- [ ] Add/remove target language (`addProjectLanguage` / `removeProjectLanguage`)
- [ ] Add/remove export format (`addProjectFormat` / `removeProjectFormat`)
- [ ] Configure sync binding (`configureSync` / `removeSync`)
- [ ] Manage permissions (`addProjectPermission` / `removeProjectPermission`)

### 2.5 — Document Management
- [ ] Link document (`addProjectDocument`)
- [ ] Unlink document (`removeProjectDocument`)
- [ ] Set document translation (`setDocumentTranslation`)
- [ ] Transition document translation state (`transitionDocumentTranslation`)

---

## Phase 3: String Detail & Translation Editor — Future

Full string translation editing UI (the most complex localization page):

- [ ] String detail header (key, context, tags, placeholders)
- [ ] Context metadata attachment panel with visual preview
- [ ] Source text editing (source language)
- [ ] Per-language translation editing with tabs
- [ ] Plural category management (ZERO, ONE, TWO, FEW, MANY, OTHER)
- [ ] Translation history with state transitions
- [ ] Workflow state management with context-aware action buttons

---

## Navigation Structure

The localization subsystem uses this nav (from `useSubsystems.ts`):

| Nav Item | Route | Status |
|----------|-------|--------|
| Locales | `/localization/locales` | ✅ Projects list |
| Strings | `/localization/strings` | Future |
| Translation Memory | `/localization/memory` | Future |
| Glossary | `/localization/glossary` | Future |
| Translation Jobs | `/localization/jobs` | Future |
| Auto-translate | `/localization/auto` | Future |

---

## GraphQL Operation Reference

### Queries
- `GetLocalizationProjects` — list all projects (paginated)
- `GetLocalizationProject` — single project by ID (with strings, languages, progress)
- `GetLocalizationString` — single string by ID (with translations, history)
- `GetLocalizationStringByKey` — single string by project+key
- `TranslationProgress` — aggregate counters per project+language
- `TranslationsByState` — translations filtered by workflow state
- `TranslationsByOrigin` — translations filtered by origin (human, AI, import, sync)

### Mutations
- `addProject` / `editProject` / `deleteProject`
- `addString` / `editString` / `deleteString`
- `setTranslation` / `setPluralTranslation`
- `transitionTranslation` / `transitionPluralTranslation`
- `bulkTransition`
- `addProjectLanguage` / `removeProjectLanguage`
- `addProjectFormat` / `removeProjectFormat`
- `addProjectDocument` / `removeProjectDocument`
- `setDocumentTranslation` / `transitionDocumentTranslation`
- `configureSync` / `removeSync` / `syncFromProvider` / `syncToProvider`
- `addProjectPermission` / `removeProjectPermission`
- `addStringMetadata` / `removeStringMetadata`
