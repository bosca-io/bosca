# Disk queue simplification — 2026-11-13

The disk implementation now uses whole sealed segments as deliveries. Acknowledgment
deletes the segment and syncs the directory; retry or restart replays the retained
file. RAF appends, count/time rollover, checksums, process locks, capacity limits,
crash-tail recovery and corruption quarantine remain. Per-record leases,
checkpoint journals, snapshots, compaction, producer deduplication and reader-limit
settings were removed. The nine `Disk*.kt` production files total 507 lines,
compared with 20 files and 1,259 lines before this sweep. Iceberg staging now uses
one mutex and retains a failed file until its append succeeds.

Whole-segment replay can duplicate already committed events. Disk enqueue retries
can also duplicate events. Transforms and sinks must tolerate at-least-once delivery.

## Measurements

GraalVM Java 25.0.3, macOS 27.0.1 ARM64, local APFS, 1 GiB heap, production fsync
enabled, without test or coverage instrumentation. The queue matrix used three
repetitions, 20,000-event enqueue/drain cases, five-second paced loads, a finite
burst and abrupt-process recovery. Synthetic events carried 256 bytes of padding.
See [raw measurements and source hashes](2026-11-13-simplification.json).

| Queue operation | Median events/sec across three trials |
| --- | ---: |
| Enqueue, 1 event/call | 16,196 |
| Enqueue, 10 events/call | 35,651 |
| Enqueue, 100 events/call | 350,586 |
| Enqueue, 1,000 events/call | 503,938 |
| Drain, single-event frames | 163,767 |
| Drain, 100-event frames | 643,238 |

Every drain/live/recovery integrity check passed. At an offered 10,000 events/sec
with one worker and 100-event batches, producer throughput stayed within 2 events/sec
of the offered rate. Handoff p99 was 1.056–1.072 seconds, including the one-second
rollover. All three runs drained completely. Recovery of 105,000 events took
8.8–44.7 ms before draining. Receive and acknowledgment measurements now cover
whole segments, so per-record acknowledgment timings from earlier reports are
not directly comparable. Burst throughput varied substantially; these short runs
are local measurements, not deployment capacity limits.

The separate 60-second pipeline run committed all 600,000 healthy events at an
offered 10,000 events/sec while an earlier 10,000-event segment retried 12 times.
Healthy ID read-back found no loss or duplication; the failed file remained
recoverable after consumer restart. Handoff p99 was 1.342 seconds. Iceberg flush
p50/p95/p99 was 107.7/151.9/830.6 ms across 60 commits. Ten-second observations held
20,000 sealed pending events: the retained failed segment and current healthy work.

This exercises the local queue, protobuf staging, event-record transform, Parquet
and local `HadoopTables` commits. It excludes HTTP, domain transforms, the deployed
catalog/object store and deployment volume. No production environment was loaded.
Repeat against the deployment's actual volume and storage before setting capacity.

## Validation

- 757 analytics tests and 6 collector tests passed, with no failures or skips.
- Analytics coverage: 99.2136% line and 98.1328% branch; unchanged 99%/98% gates pass.
- Processor, API server and runner Kotlin compilation passed.
- GraalVM native disk-queue producer/recovery smoke passed in separate processes.

Run from the workspace root:

```bash
./gradlew --continue :analytics:analytics:test :analytics:analytics:koverVerify \
  :analytics:analytics:koverXmlReport :analytics:analytics-collector:test \
  :analytics:analytics-processor:compileKotlin :server:bosca-server:compileKotlin \
  :server:bosca-runner:compileKotlin :analytics:analytics:nativeDiskAnalyticsQueueSmoke
./gradlew :analytics:analytics:benchmarkDiskAnalyticsQueue \
  --args='--mode=all --events=20000 --seconds=5 --repeats=3 --output=/tmp/queue.jsonl'
./gradlew :analytics:analytics:benchmarkDiskAnalyticsPipeline \
  --args='--seconds=60 --rate=10000 --output=/tmp/pipeline.json'
```

The measurements used the benchmark main classes directly with the Gradle-built
benchmark runtime classpath and the same 1 GiB JVM settings. The Gradle commands
above are the maintained entry points for reproducing those workloads.
