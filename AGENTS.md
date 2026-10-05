# AGENTS.md

## What this repository is

`bosca-workspace` is the single Git repository and Gradle multi-project build for the Bosca platform. The root `settings.gradle.kts` maps component directories to project paths, including `bosca-server`, `bosca-runner`, the `bosca` CLI, BML web applications, and the `web` Nuxt workspace.

Top-level directories share this Git repository. This file covers workspace-wide guidance; component `README.md` and design documents describe local commands and architecture.

## Tooling and operations

- Prefer IntelliJ MCP tools for code search, usages, refactoring, creation, and other filesystem work when those tools are available.
- Use Bosca Work Ops for specifications and planning when that integration is available, and keep the work item current.
- Preserve unrelated changes. Check workspace Git status before and after editing.
- Do not commit, push, switch branches, or rewrite history unless the user explicitly requests it.
- Keep changes in the directory that owns the code. Cross-module source changes belong in their respective component directories and are tracked by the same repository.
- Never edit or commit generated output such as `build/`, `.gradle/`, `.nuxt/`, `.output/`, or `build/generated/ksp/`.
- Finish all in-scope implementation and validation. Do not leave avoidable stubs, TODOs, or known failures.

## Build and run from the workspace root

Always run Gradle commands from the workspace root. Do not publish to Maven Local merely to connect cross-module changes. The root build substitutes `io.bosca:*` coordinates with local project dependencies.

```bash
./gradlew build                          # Build every subproject
./gradlew test                           # Test every subproject
./gradlew clean                          # Clean every subproject
./gradlew publishToMavenLocal            # Publish library subprojects; applications are excluded
./gradlew :server:bosca-server:run       # Run the GraphQL API server
./gradlew :server:bosca-runner:run       # Run the background job processor
./gradlew :server:bosca-server:nativeCompile # Build the server GraalVM native image
./gradlew :cli:nativeCompile             # Build the native bosca CLI
```

Task paths use `:<component>:<subproject>:<task>`. Use an explicit subproject path for filtered tests so task options such as `--tests` reach the correct task.

```bash
./gradlew :workops:workops:test --tests "bosca.workops.service.SpecServiceTest"
./gradlew :workops:workops:test --tests "bosca.workops.service.SpecServiceTest.create mints key and writes history"
./gradlew :content:core-content:test
./gradlew :workops:workops:kspKotlin
```

Every project is addressable from the root.

### Local infrastructure

The root `docker-compose.yaml` includes `server/services/docker-compose.yaml`. Start the stack from `server/`:

```bash
cd server
docker compose up -d
```

The stack includes PostgreSQL on port 5433, Dragonfly on 6380, NATS on 4222, and supporting services. The `web/` compose file provides a larger frontend stack with services such as Meilisearch, Trino, S3Proxy, Jaeger, and the image processor.

### Frontend

The `web/` directory is a pnpm 10 monorepo. The primary application is `projects/studio`.

```bash
cd web
pnpm install
pnpm --filter @bosca/studio dev          # Studio dev server on port 3000
pnpm lint
pnpm typecheck
pnpm test
pnpm codegen
```

Use the most focused package or project command that validates a change. Do not hardcode backend URLs in client code; browser API calls use the existing Nuxt proxy and client abstractions.

## High-level architecture

### Multi-project build composition

`settings.gradle.kts` is the workspace map. Its `includeComponent(dir, modules)` helper includes component directories as subprojects. The root build substitutes `io.bosca:<module>` with the matching local project. For example, `io.bosca:core-content` resolves to `:content:core-content` in this workspace.

Foundation and composition components include:

- `services-di`: Kotlin Multiplatform DI container and KSP processors.
- `bosca-core`: Netty server, GraphQL engine, database pool, cache, NATS, security, storage, and scheduler foundations.
- `bosca-yks`: YKS core.
- `bosca-graphql`: GraphQL client, server, and Gradle tooling.
- `bosca-kmp`: shared KMP client modules.
- `server`: API server and runner composition roots.
- `cli`: standalone CLI and embedded MCP server.

Domain components cover content, search, social, communications, AI, work operations, experimentation, BX, BML, calendar, scripting, pipelines, ecommerce, feeds, Git hosting, backup, artifacts, analytics, administration support, integrations, Kubernetes, gateway, shared queues, and Firebase Scrypt.

Application projects such as `cli`, `apps/notifications-web`, and `apps/bosca-messages` produce binaries or applications and are excluded from the workspace's aggregate Maven publication task.
`bml-server` also serves as an embedded library for BML applications and is included in Maven publication despite its application entry point.

### Core and implementation split

Backend domains generally pair a `core-<name>` contracts module with a `<name>` implementation module:

- Core modules contain interfaces, models, and annotations and avoid infrastructure dependencies.
- Implementation modules contain PostgreSQL repositories, GraphQL controllers, Flyway migrations, jobs, and services.
- Cross-domain consumers depend on `core-*` contracts and call service interfaces. Do not reach into another domain's implementation module.

### KSP-generated wiring

The platform uses KSP for compile-time wiring instead of runtime reflection. Follow existing annotations and generated-code patterns:

| Annotation | Generated responsibility |
| --- | --- |
| `@Repository` and `@Query` | JDBC repository implementations from SQL with named parameters |
| `@ServiceImplementation` | DI provider registration |
| `@TypeController` and `@Field` | GraphQL resolver wiring |
| `@JobDefinition` | Job executor providers and enqueue helpers |
| `@JobEvent` | `dispatch()` extensions that enqueue jobs and publish events |
| `@Schemas` and `@Schema` | Per-module GraphQL schema merging |
| `@RouteController` | REST route handler registration |

Each module declares an appropriate `ProviderRegistrarPrefix`, such as `Bosca`, `BoscaRunner`, `Git`, or `GitServer`, so a composition root can select its registrars. Generated files belong under `build/generated/ksp/` and must not be committed.

### Infrastructure abstractions

Domain code must use Bosca's abstractions rather than concrete messaging, cache, or database products:

- `CacheManager`: distributed cache selected by configuration. Access it through established `ServiceCache` or `RequestCache` patterns rather than a backing client.
- `PubSubService`: Redis- or NATS-backed pub/sub. Its subscriptions return Kotlin `Flow`; events use kotlinx.serialization.
- `JobQueue`: durable NATS JetStream- or Redis-backed jobs with at-least-once delivery. Enqueues are deferred until the active database transaction commits.
- `ConnectionPool` and `ConnectionManager`: bounded PostgreSQL connections with a lazy per-request handle. Blocking JDBC work runs on the virtual-thread `DatabaseDispatcher`.

Operational data belongs in PostgreSQL; analytical data belongs in Iceberg and Trino. Do not introduce direct Redis or NATS dependencies into domain code.

### Composition roots and clients

- `server/bosca-server`: GraphQL API composition root. Keep domain logic out of it; it selects domain modules and loads KSP-generated providers. It supports GraalVM native image compilation.
- `server/bosca-runner`: background-job composition root. It uses a domain subset and a separate provider prefix.
- `cli`: Clikt-based CLI compiled to a native `bosca` binary. It embeds the MCP server and uses Apollo-generated GraphQL clients.
- `web`: Nuxt 4 and Vue 3 pnpm monorepo. Development API calls proxy through the Nuxt server to the backend on port 8080.
- `gateway`: GraphQL gateway that discovers and stitches downstream schemas through introspection and creates unions for overlapping fields.

## Cross-module conventions

- I/O boundaries are async-first and should be `suspend` functions.
- Avoid `!!`; prefer safe calls, `let`, Elvis expressions, or restructuring so non-nullability is evident to the compiler.
- Never use `GlobalScope` and never catch or swallow `CancellationException`.
- Put dependency versions in the shared root `gradle/libs.versions.toml`; never inline versions in Gradle build files. Dependency repositories are configured in root `settings.gradle.kts`, and publication repositories in root `build.gradle.kts`.
- JVM modules use the Java 25 toolchain.
- Mutable entities generally have `version: Long` for optimistic locking and `deletedAt: OffsetDateTime?` for soft deletion.
- Mutations to versioned entities generally append a `FieldChange` JSON array to a partitioned `*_history` table. Follow the owning module's established migration and repository patterns.
- Preserve GraalVM native-image compatibility. Avoid unregistered reflection, runtime classpath scanning, and unsupported dynamic behavior.
- Follow existing kotlinx.serialization conventions for models and event payloads.

## Durable engineering guidance

These rules capture recurring design decisions and failure modes that apply across the workspace. Re-check the current source before relying on an implementation detail that may have changed.

### Collaboration, scope, and evidence

- Stay within the layer and product area the user named. A pipeline or configuration task does not authorize extending the runner, restructuring repositories, or adding a new platform abstraction.
- Keep client-branded projects separate from platform repositories, platform documentation, and platform WorkOps tracking unless the user explicitly requests cross-project work.
- Follow established Bosca concepts and patterns before inventing another type, service, registry, or framework layer. Reuse an existing service when it owns the responsibility.
- When terminology is ambiguous, check the existing vocabulary and code. If one interpretation duplicates an existing feature, investigate the missing type-level or domain-level meaning instead of building the duplicate.
- Read the actual framework, DI, configuration, or generated-code source before explaining its behavior. Do not invent plausible internals, incidents, usage statistics, version behavior, or operational facts.
- Documentation and marketing material describe functionality verified in the current code. Do not add roadmap claims, speculative limitations, WorkOps IDs, or unverified failure stories.
- Public distribution uses GitHub: CLI releases from `github.com/bosca-io/bosca` (tags `cli-v<version>`) and images from `ghcr.io/bosca-io/bosca/<name>`. Otherwise, do not introduce GitHub links, comparisons, or named references into Bosca UI, documentation copy, examples, or generated content.
- Recommendations must preserve valid workflows such as local development, intentionally long sessions, and the existing browser auth model. If disruption is warranted, include a concrete migration or operational path.
- Add KDoc when adding or changing interface methods. Keep implementation comments about the code's enduring contract; put bug-history narratives in change history rather than inline comments.
- Preserve published SDK signatures. Extend them additively with overloads or new entry points instead of removing or changing existing forms.
- Do not swallow, soften, or narrow exceptions merely to make a failure disappear. Preserve cancellation and loud failures unless the established contract explicitly calls for recovery.
- Do not turn catalog or configuration validation into fatal server-startup failure without explicit direction; established startup paths log recoverable configuration problems and continue.
- Browser checks against a real authenticated session must be deliberate and non-destructive. Prefer read-only checks; use isolated scratch records for mutations and restore through the API, not raw SQL. Never perform random or chaos clicking.
- Investigate every validation failure. Do not dismiss a failure as pre-existing without evidence; fix failures caused by the change and report genuinely unrelated blockers precisely.
- Do not call work complete, done, fully tested, or production-ready without measuring the applicable quality bar. Passing test counts are not coverage; where Kover gates matter, report both line and branch coverage and any remaining gap.
- A legacy port is complete only after source-level feature-parity review and relevant behavioral tests, not after models and happy paths alone.
- Wiring-heavy behavior needs a real integration test, normally with Testcontainers, in addition to mock-based unit tests.

### Domain boundaries, models, and authorization

- Prefer constructor injection. Use `provide<T>()` only where the existing lifecycle or generated wiring requires service lookup.
- Controllers and route handlers call services, never repositories. A service may use its own aggregate's repository; cross-aggregate access goes through the owning aggregate's service.
- Authorization checks at the API boundary belong in GraphQL controllers by design. Preserve the established distinction between boundary authorization and reusable domain behavior.
- Define one plainly named model per concept in the appropriate `core-*` module; do not add parallel `*Row`, `*Entity`, or `*Dto` persistence models in implementation modules. Put mapping and serialization annotations on the core model.
- For table-scoped model fields, prefer `key`, `created`, and `modified`, not redundant `<entity>Key`, `createdAt`, or `updatedAt`. API arguments may remain descriptive.
- A `Principal` is an authenticated identity; a `Profile` is a separate social/CRM identity with a separate ID. Permissions are granted to groups, not directly to principals. Organizations group profiles but are not tenants and do not scope content visibility; Bosca is effectively single-tenant.
- Reads generally filter inaccessible entities through `PermissionEvaluator`; writes verify access and fail. Preserve entity ACLs, group membership, public/published rules, and parent permission behavior.
- Store specification and other rich document bodies as ProseMirror JSON through the content system, never as plain-text substitutes.
- When WorkOps creates a requirement and its linked task, populate both the requirement body and the task's independent `descriptionMarkdown`.
- In pipeline vocabulary, cast means reinterpret JSON or an interface value as a catalogued type. Scalar conversion belongs in JSONata and is not a cast node.
- PostgreSQL is for operational state. Iceberg/Trino is for analytical, historical, and time-series data. Use `PubSubService` for real-time events and the cache abstractions for hot data; never design domain code against NATS or Redis directly.

### Repositories, GraphQL, migrations, and installers

- Repository mapping uses exact property names. Annotate every snake_case column with `@ColumnName`; fake repositories and unit tests may not reveal a missing annotation.
- Use `JsonbMapper` and property-level `@DbMapper` for strongly typed JSONB models and collections. `JsonElement` is already handled by GraphQL JSON scalars and repository JSONB mapping; do not round-trip it through `String`.
- A count-returning DML `@Query` must set `returnUpdateCount = true`.
- GraphQL is schema-first. Kotlin default arguments do not make a non-null SDL argument optional, and every GraphQL object needs a `@TypeController` with explicit `@Field` resolvers for its SDL fields.
- Keep major GraphQL controllers in focused files instead of building controller monoliths.
- Adding a GraphQL module to a composition root requires all wiring layers: schema registrar, query/mutation namespace accessor, nested namespace accessors where present, and dispatcher registrar. DI provider/package registration alone does not expose the schema.
- A new SQL migration must be named and registered in the owning migration provider's resource list. An unnamed `Migration` provider can be clobbered by type-slot registration, and an unlisted `.sql` resource never runs.
- Persisted enum additions require the Kotlin enum, GraphQL SDL enum, and a PostgreSQL `ALTER TYPE` migration. GraphQL-facing enum constants are uppercase and do not use `@SerialName`; SQL mapping handles database casing.
- A `PackageInstaller` rerun is gated by both the package installation version and the individual installer version. Bump both when shipping new seed data, keep the installer idempotent, and verify the boot-time installer logs.
- If an edited field silently fails to save, check both the service's `copy(...)` and the repository `UPDATE` column list.

### Multi-project builds, KMP, and generated output

- Project paths in Gradle scripts must use the full root path, such as `:content:core-content`.
- Workspace substitution maps the base KMP coordinate such as `io.bosca:<module>`, not a `-jvm` secondary coordinate. Tool and worker configurations should request the base component with JVM attributes.
- Profiles Web deliberately keeps its BML library and Gradle-plugin version pins on an available published release while coordinated BML protocol changes are still being developed in this workspace. The workspace may therefore contain Profiles consumer changes before a compatible BML version can be named. Do not report the unchanged pin or require a pin to an unpublished BML version as a review finding; revisit the pin only after the compatible BML release has been published or when explicitly reviewing release readiness against available artifacts.
- Plugins supplied by the root `buildSrc` classpath are requested by ID without a version in subprojects.
- When a supposedly fixed test is restored from cache, verify with `--rerun-tasks --no-build-cache` before trusting the result.
- A runtime `NoSuchMethodError` whose descriptor ends in `Continuation` usually indicates a stale jar after a method became `suspend`; rebuild and deploy matched core and implementation artifacts before changing current source.
- Tasks that start servers must not be cacheable or up-to-date-skippable; use `outputs.upToDateWhen { false }` for affected GraalVM run tasks.

### Services, jobs, caches, and runtime behavior

- Call generated event `dispatch()` directly inside the transaction. Job enqueueing already defers until commit; do not wrap dispatch in another `afterCommit`.
- Verify a workflow's real dispatch path before attaching or restoring an event. Content transition jobs are named-state jobs started by `Transitioner.beginTransition`, not automatically `@JobEvent` jobs.
- Workflow transition completion has executor and listener paths selected by `states.job_name`; state-entry side effects must be implemented and tested on both paths.
- The `queue` on `@JobDefinition` is the DI provider name, not the physical queue name. Interfaces resolved through `provide<T>()` must satisfy the service registration contract.
- Durable wait nodes use `willSuspend` and `doSuspend`, enqueue their typed job helper, and poll on redelivery; an event is only the fast path. Do not override the ordinary run path to simulate suspension.
- Cross-queue child jobs must retain the parent's queue so completion notification can find the parent through `JobQueueRegistry`.
- Raw pub/sub collectors that call services using request-scoped caches or database connections must establish both request-cache and connection-manager context.
- With multiple `ServiceCache` instances, perform a broad `clear()` before queued per-key `remove()` or `put()` operations because local clearing drops pending remote operations.
- A request owns a single database connection. Do not parallelize database queries within that request using sibling `async` blocks.
- Question whether locking is needed before adding it; avoid JVM `synchronized` in platform code.
- Keep `SelectiveContentCompressor` work on `codecGroup`, off the Netty event loop.
- `NettyServerEngine.stop()` halts the JVM and must not be called from tests. Reuse a daemon-thread test server when exercising the real engine.
- `ApplicationConfig` is not a DI service. Inject `BoscaApplication` and read `application.environment.config` when a DI-created service needs configuration.
- The Kubernetes controller is a separate HTTP service. Other modules use `KubernetesControllerClient`; do not introduce an in-process Helm service.
- Server, runner, and analytics processor maintain independent registrar lists. When a shared provider gains a dependency, verify registrar parity in every composition root that loads it.

### Native-image and serialization constraints

- Native CLI and KMP/native paths use explicit `.serializer()` instances. Avoid reified `serializer<T>()`, `typeOf`, and reflection-dependent helpers in GraalVM code.
- In KMP clients, parse structured data with kotlinx.serialization models rather than hand-extracting `JsonObject` fields.
- Resource directories loaded with `getResourceAsStream` must be included in the native application's `resource-config.json`.
- Maintain reachability metadata per native application. Libraries that dispatch methods by name may need `allDeclaredMethods`, and every native composition root using the Netty engine needs its handler metadata.
- JGit `Config.getEnum()` reflectively invokes enum `values()`; register those methods in the Git implementation's native metadata.
- K2 compiler plugins should be tested in process with the embedded compiler and also compiled for common metadata and a representative native target; JVM success alone does not prove KMP compatibility.

### Pipelines and agent tooling

- Pipeline node settings are declarative: `@SettingSlot` metadata flows through KSP and GraphQL to the generic Studio renderer. Do not reintroduce per-node-kind forms in `PipelineNodeInspector`.
- Pipeline setting keys must not be `type`, `id`, or `position`; those names collide with the flat stored-node discriminator and envelope.
- Use the KSP-generated typed node serializer for pipeline I/O instead of hand-decoding slots.
- Implement node previews through the dedicated `dryRun()` method; do not inline `context.dryRun` branches into normal execution.
- Connection validation permits one kind per slot and treats unknown compatibility as blocked. Use explicit bridge nodes for type changes rather than weakening validation.
- Manual, API, and resumed pipeline runs are job-driven and retain the caller's principal end to end. Inline orchestration can orphan suspending runs.
- The singular pipeline `public` flag currently combines viewing and execution. Treat a future split as a dedicated design change; do not patch one bypass ad hoc.
- Code-backed MCP tools implement the shared `IAgentTool` contract and stay dependency-light by executing through `GraphQLService` under the caller's authentication. Use code tools for conditional or multi-step logic; seed simple operations as GraphQL tools.
- MCP tool wiring has three parts: an `IAgentTool` implementation, a named provider whose name equals the tool key, and a code-backed tool seed. New installer-backed seeds require the corresponding package version changes.

### Studio, Nuxt, and BML

- Studio is the supported administration surface for cross-cutting UI and security work.
- Studio has unified per-user administration pages, not duplicate `My ...` self-service pages. Do not show WorkOps specification or requirement IDs in user-facing UI.
- Use CodeMirror for code editing and `json-editor-vue` for JSON values; do not introduce Monaco or plain text inputs for structured JSON.
- Global and theme CSS belongs in real CSS files. Prefer scoped component styles over inline style attributes.
- Kubernetes views in Studio derive from backend data and real labels; never hardcode cluster objects or mock operational values into production UI.
- The canonical Nuxt auth path uses `@bosca/auth-client-browser`, its Nuxt plugin, Nitro middleware for SSR gating, client middleware for SPA navigation, and existing proxy routes. Do not hand-parse auth cookies.
- Browser-readable tokens and client-side refresh scheduling are intentional parts of the auth library. Do not replace them with an HttpOnly-cookie-only design without an explicit auth redesign.
- Packages imported from `@bosca/*` may need `vite.ssr.noExternal` because of their extensionless ESM imports.
- BML applications prefer declarative `@click` and `@submit` islands. Do not build UI through query-selector listener piles or call GraphQL directly from client islands; use the server/proxy abstractions.
- BML pages currently own their full document skeleton because template/layout declarations are no-ops. Scripts must be declared `server` or `client`; plain or inline script tags are invalid.
- Boolean BML attribute interpolation uses HTML presence semantics: true emits the attribute and false omits it. Match with presence selectors and set/remove operations, not string value selectors.
- The BML same-origin `/graphql` proxy forwards the resolved token. Keep browser calls proxy-first and avoid adding CORS-dependent direct backend calls.
- `@bosca/ui` selects treat the empty string as unset; use an explicit sentinel value for an `All ...` option.
- Vue `ref<T[]>` unwraps inner refs. Use a plain array plus an explicit revision signal when the elements themselves must remain refs.
- Exclude proxy routes such as `/graphql` and `/api/**` from generated sitemaps, and keep `nuxt-site-config` explicit where the installed sitemap version requires it.
- Studio editor dirty state is a Yjs attribute affected by every post-ready update. Flush schema normalization before signaling ready, and preserve server dirty flags when reseeding attributes.
- Guide step titles come from the step document's first level-one heading. Do not improvise alternate titles while creating guides.

### Product presentation and operational assumptions

- Marketing copy uses plain, concrete product language. Avoid absolute promises about human behavior, invented product vocabulary, implementation jargon, correctness framed as a feature, accident-prevention framing, and clever phrasing that says nothing.
- Use the product's existing nouns and describe only the role Bosca actually performs. For example, packing support produces suggestions; it does not claim to pack physical orders.
- Bosca transactional email designs are light-themed. Preserve the target brand and do not transplant another project's visual skeleton.
- Favicons use the real brand mark on a transparent background; do not invent simplified glyphs or background tiles.
- Development-only defaults in Compose and `application.yaml` are intentional. Do not report them as production-critical secrets without tracing their deployment scope.
- Initial-administrator password logging on a fresh install is intentional bootstrap behavior. Do not remove it as generic credential logging without an explicit design change.
- Request body limits and rate limiting are gateway/ingress responsibilities. Do not add duplicate handler-level controls merely because they are absent from application code.
- Admin-only URL inputs rely on administrator authorization and the gateway/ingress model. Do not add address-denylist SSRF validation to them without an explicit security architecture change.
- Production Helm values belong to each deployment's own operations repository. Do not copy deployment values into this repository.
- Do not assume ReadWriteMany storage. Design shared storage around ReadWriteOnce and pod co-location.
- Test and development PostgreSQL images must include pgvector when migrations use vector types; use the established `pgvector/pgvector` image rather than plain PostgreSQL.
- Prefer a one-pod baseline plus HPA and an appropriate `maxUnavailable` disruption budget in user values unless fixed high availability is explicitly required.
- The Bosca MCP/CLI connection may target a deployed environment rather than localhost. Confirm the target before creating or mutating data. If MCP auth is stale, perform a full login and reconnect rather than rotating credentials underneath a running MCP session.
- CI shell `run:` steps do not receive Git credentials automatically; only the established Git actions inject authentication. Preserve that distinction when changing release workflows.

## Working across components

- Cross-component source changes are resolved directly by the multi-project build. Do not publish intermediate artifacts to connect them.
- Read each affected component's `README.md` or `AGENT.md` when present before editing it.
- Check workspace Git status before and after changes; all components now share one index and history.
- Do not commit without explicit approval.

## Validation expectations

- Add or update tests for behavior changes and regressions.
- Prefer focused tests, KSP tasks, frontend linting, type checks, and code generation for touched modules before broad workspace tasks.
- Run integration tests only with their required infrastructure available.
- Review the workspace diff and status, including every affected component, before handing work back.
- Report exactly what was run, what passed, and what remains unverified. Do not claim validation that did not run successfully.
- Breaking changes, things where we need to consider compatibility with older versions, is typically only a concern with the GraphQL layer.  Internal services, repositories, etc are not client facing.  So, before you make a decision to 
  include backwards compatibility concerns, this is a discussion point, not something you are allowed to pre-decide.  Backwards compatibility is considered technical debt and should only be done with consent.
- Offset pagination is perfectly fine.  That's how everything else is done right now.  I prefer the consistency.  I don't want a new way of pagination right now.
