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

The database-backed query, dashboard, visualization, script-binding, and error-group tests
migrate a PostgreSQL template once per class. Every test copies that template into its own
database with a separate client pool. Tests using NATS retain separate accounts.

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
Publishes for a batch are sent asynchronously before waiting for acknowledgments.

NATS publications use message IDs derived from the app ID, device `installation_id`,
context `user_id`, and each event's `client_id`. Keep those values unchanged when
retrying, including after a lost HTTP response or a partially published batch.
Anonymous users can still deduplicate within their installation. Events without
an installation or event ID are published without deduplication, so retries can
produce duplicates. Deduplication lasts for the stream's configured duplicate window.
This does not provide unlimited deduplication
or prevent repeated transform and optional-sink side effects.

The processor acknowledges a processing group after its Iceberg commit and request
cleanup succeed. Concurrent processing groups share one data file and table commit.
A commit starts when every consumer worker is waiting on storage, when the waiting
groups hold `iceberg.commitMaxRecords` events (default 100,000), or
`iceberg.commitIntervalMillis` after the first group starts waiting (default
10,000), whichever comes first. A write or commit failure leaves every group in
that commit eligible for redelivery, and none of their events stays buffered. A
transform failure retries only that delivery; healthy peers in the fetched group
can still commit and be acknowledged.

Direct Iceberg ingestion stages events in a local file instead. A failed staging
file stays pending and must commit before another file can accept events. Staging
append and flush operations share one mutex. Further staging writes retry a failed
append first, so an outage does not accumulate another staged copy on every
retry. Staging storage must survive pod replacement to preserve staged data
during recovery.
An interrupted staging write is truncated back to its frame boundary before the
file is reused or flushed. A failed rollback remains retryable and prevents new
writes. Parquet record-write errors fail the flush and retain the staged data.

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

### Confirming FUMS delivery

Filter collector and worker logs for `FUMS`. These messages are visible at the
default INFO level. When the transform list is assembled, the collector logs
`FUMS event processing enabled: queue=fums`. Disabled processing is logged explicitly;
enabled processing without a registered queue produces a warning. Processing being
disabled on a worker does not disable its separate `fumsQueueRunner` delivery worker.

Open a Bible chapter in the reader or start a recording, then follow the same
`jobId` through these messages:

| Message | Meaning |
|---|---|
| `FUMS usage submitted` | The transform recognized a valid usage event and submitted its job to the queue abstraction. If a database transaction is active, publishing waits for commit. |
| `FUMS delivery processing` | A worker picked up the job and started resolving its token. |
| `FUMS delivery accepted by API.Bible` | The reporting endpoint returned a successful HTTP response; `httpStatus` records the status. |
| `FUMS delivery skipped` | No token was available for that exact content reference. The token may be missing or expired; no report was sent. |
| `FUMS delivery failed` | The request failed or returned a non-success status. The exception still reaches the job queue for its normal retry handling. |

Check `bosca-runner` logs, or the API server logs when `fumsQueueRunner` runs there.
Generic job completion alone does not prove delivery: skipped reports also complete.
An accepted message confirms HTTP acceptance by API.Bible, not downstream usage
aggregation. If submission appears without processing, check that `fumsQueueRunner`
is enabled and connected to the same job transport. If processing appears without
an outcome, check the job ID in worker errors, including token lookup failures.
These FUMS messages omit tokens, request URLs, response bodies, and device, session,
and user identifiers.

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

## Local event spooling

`AnalyticsEventQueue` selects NATS or a persistent local-disk spool using
`analyticsEventQueue.type`. The collector forwards through the queue when
`eventRepository.type=queue`; the processor applies its transforms and flushes
final analytical storage before acknowledging a processing group. The disk backend
appends collector batches to a `RandomAccessFile`, rolling segments after 10,000
events or one second (including idle rollover). These limits are configurable.
It syncs once per enqueue. One sealed segment is one delivery containing its
original event batches. The processor transforms and stores those batches,
flushes final storage, then acknowledges by deleting the segment and syncing the
directory. A failure or consumer restart replays the whole retained segment;
events already committed within that segment can be duplicated. Transforms and
sinks must tolerate replay. Disk enqueue retries can also duplicate events; the
disk spool does not maintain a client deduplication index.

The consumer process owns an exclusive file lock. It needs no per-record leases,
checkpoint journal, or compaction. Status counts sealed pending events and all
data bytes; unsealed events enter the pending count at rollover. Retrying a segment releases its claim and
applies an in-memory delay while other files remain available. Worker count
bounds concurrent processing; the disk backend returns one segment per receive.
Collector queue initialization acquires the producer role and seals recoverable
active data before accepting traffic, so restart recovery does not require a new
event. Consumers and status observers do not acquire the producer role. Both
sealed files and interrupted files with intact footers must match their declared
event and frame counts; mismatches are preserved in quarantine.
PostgreSQL is not an
analytics event-queue backend. Live sessions can be explicitly disabled with
`liveSessions.type=disabled` when NATS/Redis are absent.

See the [installation and recovery settings](../server/docs/postgresql-installation.md)
for directory ownership, persistence, capacity, quarantine, and topology constraints.
