# Disk analytics queue benchmarks

Run from the workspace root with Java 25:

```bash
./gradlew :analytics:analytics:benchmarkDiskAnalyticsQueue \
  --args='--mode=all --events=50000 --seconds=10 --repeats=3 --output=/tmp/analytics-queue.jsonl'
```

The application runs in a separate JVM with a 1 GiB heap and without test/Kover
instrumentation. It uses the actual `DiskAnalyticsEventQueue`: file and directory
syncs, checksums, whole-segment delivery, 10,000-event segments, and one-second age rollover remain enabled. The recovery
case uses a ten-minute age limit to keep an open segment when its isolated writer
child exits through `Runtime.halt(0)`.

Every run uses disposable spool directories and synthetic identified events on
the selected filesystem. `--root=/path/to/local-volume` selects that filesystem;
it does not use an existing application spool. `--output` must name a fresh file.
Completed cases print and append JSON records, so partial results survive a
failed run. The harness deletes its scratch spools after each case.

Options:

| Option | Default | Meaning |
| --- | --- | --- |
| `mode` | `all` | `enqueue`, `drain`, `live`, `recovery`, `status`, or `all` |
| `events` | `50000` | Enqueue event count; drain caps at 20,000; recovery has a 105,000 minimum; explicit live-load cap |
| `seconds` | `10` | Live producer duration; an explicit live cap can end it sooner |
| `repeats` | `3` | Measured repetitions after one unmeasured 5,000-event enqueue/drain warmup |
| `padding` | `256` | Synthetic payload bytes per event, in addition to IDs/page/context/envelope |
| `batches` | `1,10,100,1000` | Enqueue batch-size matrix |
| `rate` | unset | Explicit live offered events/sec; zero produces as fast as possible |
| `batch` | `100` | Explicit live batch size |
| `workers` | `1` | Explicit live consumer worker count |

`all` includes enqueue batching, prefilled drain with one acknowledgment per
sealed segment, ten-second loads at 1,000 events/sec with single-event batches,
10,000 events/sec with 100-event batches and 1/16 workers, an unpaced burst, and
abrupt-writer recovery. Live readers use a separate queue instance and share the
spool with the writer, as independent producer/consumer roles do. The burst has
a finite event cap. Each drain/live/recovery checks every synthetic event ID for
loss or duplication and verifies no retained pending data remains.

Throughput includes synthetic payload construction where producers are timed.
Enqueue latency measures only the awaited durable queue call. Receive and
acknowledgment timings measure whole segments. Live handoff latency runs
from batch construction to durable acknowledgment; this includes rollover,
polling, queuing, and acknowledgment. Percentiles are upper bucket boundaries at
0.1 ms resolution; maximum latency is unrounded. Handoff samples retain each
collector batch's timestamp and are not weighted by event count. Heap snapshots include uncollected garbage.

These measurements isolate queue costs. They exclude HTTP, processor transforms,
request/database scopes, Iceberg/object storage, and remote-volume latency. Warm
filesystem caches and normal OS/device write-cache behavior apply. No power-loss
or physical-device durability test is performed. Repeat on the deployment's
actual persistent volume with its real event mix and final storage before using
these numbers as a production capacity limit.

The `status` mode seals a 5,000-event segment and measures 1,000 independent
observer calls. Status reads segment footers; it does not cache or parse active
payloads. Each call verifies the sealed pending count.

## Independent retries and local Iceberg endurance

Run a sustained queue-to-storage workload while an earlier 10,000-event segment
keeps retrying. Healthy later segments go through production protobuf staging,
the event-to-record transform, Parquet writes, and real local Iceberg commits.
After producing, the harness drains healthy work, restarts the consumer, checks
that the failed segment still exists, and verifies every committed synthetic ID
for loss and duplication. Its final acknowledgment of the synthetic failed
segment is scratch-data cleanup; production retries are never discarded by this
change.

```bash
./gradlew :analytics:analytics:benchmarkDiskAnalyticsPipeline \
  --args='--seconds=600 --rate=10000 --output=/tmp/analytics-pipeline.json'
```

`--root=/path/to/volume` selects the scratch filesystem. The JVM heap is fixed at
1 GiB. The default is ten minutes at an offered 10,000 healthy events/sec, in
100-event batches. Results include ten-second backlog and heap observations,
whole-group Iceberg flush latency, and batch handoff latency. The local table uses
`HadoopTables`; HTTP, domain transforms, request scopes, the deployed catalog and
object store are outside this benchmark. Use the deployment's actual volume and
storage configuration for deployment capacity acceptance.

## Native queue execution

With GraalVM 25 `native-image` on `PATH`:

```bash
./gradlew :analytics:analytics:nativeDiskAnalyticsQueueSmoke
```

This compiles the actual queue and its serializers into a small native executable,
then runs separate producer/consumer phases against a disposable spool. An earlier
delayed retry remains pending while later segments are acknowledged; the next
process must recover exactly that earlier work. It checks queue execution
independently of the full collector's composition and HTTP wiring. Build that
application separately with `:analytics:analytics-collector:nativeCompile`.

The [2026-11-14 review fixes and measurements](2026-11-14-review-fixes.md) record
current correctness checks, native recovery and local pipeline results. The
[simplification report](2026-11-13-simplification.md) describes the whole-segment
design. Earlier reports retain measurements of the checkpoint-based implementation.
