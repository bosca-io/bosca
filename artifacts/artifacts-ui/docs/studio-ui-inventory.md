# Studio artifact UI inventory

The Studio files below, relative to `web/projects/studio/app`, define the functional port. Atlas imports `artifacts-ui`; Git remains a
separate imported application. Both use the same GraphQL endpoint and existing authorization.

| Studio source | Atlas destination | Preserved workflow |
| --- | --- | --- |
| `components/shell/HomeLauncher.vue`, `NavigationModal.vue`, and `ScopedNav.vue` | Atlas `/` and shared design-library subsystem navigation | Compact launcher, subsystem icon/description/chevron, category/search/recents, arrow/Enter navigation, current-system indication, per-subsystem accents, transparent modal backdrop and Escape/focus behavior |
| `pages/artifacts/namespaces/index.vue` | `/artifacts/namespaces` | Names, visibility, per-protocol counts, create/delete, copy IDs |
| `pages/artifacts/namespaces/[id].vue` | `/artifacts/namespaces/{id}` | Type filter, 15-row offset pages, repository create/delete, public pull setting, namespace deletion |
| `pages/artifacts/repositories/index.vue` | `/artifacts/repositories` | Type filter, repository links, namespace links, copy IDs, deletion, raw file staging |
| `pages/artifacts/repositories/[id].vue` | `/artifacts/repositories/{id}` | Version/tag paging, expanded files/metadata, sizes/roles/digests, protocol URL copying, raw staging, version/tag/repository deletion |
| `pages/artifacts/repositories/upload.vue` | `/artifacts/repositories/upload` | Existing/new raw targets, version and filename validation, multiple files, progress, cancellation, partial-failure retry |
| `components/artifacts/RawArtifactDropZone.vue` and `useRawArtifactUploadStaging.ts` | `artifacts-drop`, browser staging/upload modules | File picker/drop, transfer into the upload form, per-file names |
| `pages/artifacts/settings/permissions.vue` | `/artifacts/settings/permissions` | Namespace choice, group choice, VIEW/EDIT/DELETE/MANAGE/LIST grants, confirmed revocation |
| `pages/artifacts/settings/repositories/[id].vue` | `/artifacts/settings/repositories/{id}` | Administrator gate and protocol-specific settings |
| `components/artifacts/ArtifactRepositorySync.vue` | `artifacts-sync` | Create/edit/delete destinations, versions, token-secret selection/creation, enabled state, paged tags, manual push, paged history and retry |
| `components/artifacts/ArtifactRepositoryPublication.vue` | `artifacts-publication` | Immutable remote identity, mutable secret/enabled state, version paging, full manifest review, exact build commit, completion confirmation, publication and paged status |
| Publication and push run links into Studio pipelines | `/artifacts/runs/{id}` | Correct platform run IDs, state/node failures, refresh, failed-run restart and active-run cancellation |

The existing nested artifact admin GraphQL resolvers require administrator membership. Atlas
honors those checks instead of weakening them for the port. Registry REST traffic separately
continues honoring namespace permissions.

Raw completion sends the entire reviewed file manifest, so the existing API rejects files changed
after review. Publication status distinguishes the current verified attempt from historical
verification. Docker and raw destination changes keep the API's optimistic-lock versions.

Validation is split between schema/model/client tests, real Atlas process integration with isolated
PostgreSQL/NATS/Meilisearch/storage, and Chrome workflows. Chrome covers native dialog submission,
staged file navigation, upload, file URL copying/reading, JavaScript errors, and eight pages at
1920, 1024, 768, and 390 pixels. External publication providers are exercised by the existing backend
integration fixtures rather than by mutating a live remote registry or release.
