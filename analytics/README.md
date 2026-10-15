# bosca-analytics

Analytics collection, processing, and AI-powered analysis for the Bosca platform. Includes a collector server that ingests analytics events, a processor that transforms and stores them, an AI analysis layer, and shared models and server client libraries. Both the collector and processor are standalone applications with GraalVM native image support.

## Modules

| Module | Description |
|---|---|
| `core-analytics` | Contracts: analytics interfaces, configuration, models |
| `analytics` | Implementation: analytics services and configuration |
| `analytics-ai` | AI-powered analytics analysis |
| `analytics-models` | Shared analytics data models |
| `analytics-server-client` | Client library for analytics server API |
| `analytics-collector` | Standalone collector server (ingests events) |
| `analytics-processor` | Standalone processor server (transforms/stores events) |

## Prerequisites

- JDK 25
- Docker (for local infrastructure)

## Build

Run these commands from the **workspace root**. `analytics` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :analytics:test  # Test analytics modules
./gradlew :analytics:analytics-collector:run  # Run the collector
./gradlew :analytics:analytics-processor:run  # Run the processor
./gradlew :analytics:analytics-collector:nativeCompile  # Build a native image
```

## Local Infrastructure

From the workspace root:

```bash
cd analytics
docker compose up -d    # Start services
docker compose down     # Stop services
```

| Service | Port | Notes |
|---|---|---|
| PostgreSQL | 5433 | Database |
| Dragonfly (Redis) | 6380 | Cache |
| NATS | 4222 | Messaging |

## Architecture

The project separates contracts (`core-analytics`) from implementations (`analytics`). Two standalone servers handle the event pipeline: the collector ingests events and the processor transforms and stores them. Both servers support GraalVM native image compilation and fat JAR packaging via the Shadow plugin. The `analytics-ai` module adds AI-powered analysis on top of the core analytics contracts. `analytics-server-client` provides a client library for other services to interact with the analytics API.

## Collector health

The collector exposes `/api/v1/collector/ready` and `/api/v1/collector/live` on
its HTTP port. A failed NATS publish or request-processing failure makes readiness
return 503. Liveness returns 503 after either failure persists for 60 seconds.
Repeated failures do not reset that grace period.

Readiness checks JetStream with a bounded timeout so NATS recovery can restore
readiness without incoming events. A processing failure clears after a subsequent
request completes successfully; otherwise the liveness probe requests a restart.
Workers log failed batches and continue with the next request. Failed batches are
not replayed because a failure can occur after storage has already succeeded.

Deploy the collector image and Helm probes together. Collector chart `0.1.2`
(services chart `0.1.5`) uses these endpoints with a five-second probe interval.

## Event delivery

`POST /api/v1/events` and its `/events` alias wait for the queued batch's primary
storage operation and request cleanup before returning 202 or 200. With NATS
storage, success requires a JetStream acknowledgment for every event. A storage
failure or a wait longer than 15 seconds returns 503, allowing clients to keep and
retry their events. The normal browser delivery path already does this using its
local event queue. In-process analytics capture continues to enqueue asynchronously.

NATS publications use message IDs derived from the app ID and each event's
`client_id`. Keep those event IDs unchanged when retrying, including after a lost
HTTP response or a partially published batch. Events without IDs use a hash of
their envelope and batch position, excluding collector receipt timestamps; those
retries must retain the same envelope and order. Deduplication lasts for the
stream's configured duplicate window. This does not provide unlimited deduplication
or prevent repeated transform and optional-sink side effects.

The processor acknowledges messages after primary storage and request cleanup
succeed. Primary Iceberg staging failures propagate, leaving messages eligible for
NATS redelivery. The existing local staging and later Iceberg commit behavior is
unchanged; staging storage must survive pod replacement to preserve staged data.

## Bible usage reporting

The Analytics pipeline reports API.Bible usage from client-authored events. Text views use
an `impression` event with `element.type` set to `bible-text`; audio playback uses an
`interaction` event with `element.type` set to `bible-audio`.

Studio's Bible reader and document reference previews use the existing automatic
impression tracking, with Bible metadata on their chapter headings. The reader sends an interaction when
a selected recording starts playing. It displays
the attribution delivered with each chapter and recording. Loading audio alone does
not report playback; buffering and pause/resume do not add another report for the
same loaded recording.

The element's `extras` identifies the content:

| Field | Value |
|---|---|
| `bible_id` | Bosca Bible metadata UUID |
| `bible_version` | Bible version |
| `bible_variant` | Exact variant |
| `bible_usfm` | Chapter USFM, such as `JHN.3` |
| `bible_audio_id` | Recording source UUID, required for audio |

The event context supplies its device installation ID, session ID, and optional user ID.
Device and user identifiers are hashed before queueing. The collector includes FUMS
in its normal Analytics transform list and enqueues reference-only jobs on the
dedicated `fums` queue. `eventProcessing.fumsEnabled` is configured only on the
collector and runner, through `FUMS_TRANSFORM_ENABLED`: the collector defaults to
`true` and the runner to `false`. The shared configuration defaults to `false`.
If the runner handles FUMS event processing instead, enable it there and disable
it on the collector. This choice is independent of the event storage and job queue
transports. The `fumsQueueRunner` worker in `bosca-runner` resolves the private token through `BibleProviderService`
and sends the [manual FUMS report](https://docs.api.bible/guides/fair-use/).
Tokens never enter client responses, queued usage payloads, or Analytics events. Missing
or expired tokens are skipped without fetching Scripture content. Pruning cached text or
audio preserves usage tokens until their original expiry so pending reports can still be delivered.
The ordinary job queue
retries delivery failures and completes successful reports.

## Dependencies

- `io.bosca:core` -- Bosca Core (platform framework)
- `io.bosca:core-security` -- Bosca Core Security (authentication)
- `io.bosca:core-graalvm` -- Bosca Core GraalVM (native image support)

## Use in this workspace

Depend on the root project path for the modules you need. For example:

```kotlin
dependencies {
    implementation(project(":analytics:core-analytics"))
    implementation(project(":analytics:analytics-server-client"))
}
```

Published `io.bosca:*` coordinates used by existing catalogs also resolve to these local projects in the root build.
