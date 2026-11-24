# PostgreSQL infrastructure and local analytics spooling

The PostgreSQL backends let the API server and runner operate without NATS or
Redis. Chat messages, reactions, read positions, typing, and presence use
PostgreSQL when `CHAT_STORAGE=postgres`. Jobs, pub/sub, cache, counters, and
locks each retain their existing backend selector.

Apply the same infrastructure settings to every process in an installation:

```dotenv
NATS_ENABLED=false
REDIS_ENABLED=false
CACHE_TYPE=postgres
COUNTER_TYPE=postgres
PUBSUB_TYPE=postgres
JOB_QUEUE_FACTORY=postgres
DISTRIBUTED_LOCK_TYPE=postgres
CHAT_STORAGE=postgres
LIVE_SESSIONS_TYPE=disabled
```

Keep the existing PostgreSQL connection, object storage, security, and domain
configuration. These settings replace the messaging infrastructure; they do not
replace other enabled domain services. Database migrations create the operational
infrastructure and chat tables through the normal application startup path.

PostgreSQL pub/sub uses one dedicated LISTEN session per application instance.
It requires a session connection; route it directly to PostgreSQL or through a
session-mode pooler.

Cache, counter, lock, pub/sub, and job queue statements use a separate
infrastructure pool of 10 connections per process, so requests holding
transactions on the main pool cannot exhaust the connections these operations
need. Publishes and job enqueues inside a transaction still join that
transaction. Idle job workers poll this pool, so raise its `maxConnections` for
processes that run many job workers. The infrastructure pool always connects to
the `database.postgres` database, because transactional publishes and enqueues are
written there; set `database.postgres-infrastructure.maxConnections` to change its
size. Size PostgreSQL's
`max_connections` for both pools, plus the LISTEN session, on every replica.

The cache reads PostgreSQL directly and has no additional local cache tier.
Expired cache entries, counters, lock records, and pub/sub payloads are reclaimed
periodically. Expiration is enforced during reads/acquisition before cleanup.

Notifications carry references to expiring payloads, including messages larger
than PostgreSQL's notification payload limit. Live pub/sub has no historical
replay guarantee; a subscriber that falls more than 64 messages behind drops its
pending messages and resubscribes. Job state and chat history are durable and can be queried after
reconnection. PostgreSQL enqueue inserts join the current database transaction and become visible
to workers only when that transaction commits. Jobs use polling and renewable database leases, so a missed
notification cannot strand pending work. Job handlers still need the existing
at-least-once/idempotency behavior.

## Analytics

Analytics event queues support `nats` and `disk`. PostgreSQL is not an analytics
spool or analytical history store. Final events still use the configured Iceberg
storage and Trino query path.

For a collector forwarding events through local disk:

```dotenv
EVENT_REPOSITORY_TYPE=queue
ANALYTICS_EVENT_QUEUE_TYPE=disk
ANALYTICS_EVENT_QUEUE_PATH=/data/bosca/analytics-events
ANALYTICS_EVENT_QUEUE_MAX_BYTES=1073741824
ANALYTICS_EVENT_QUEUE_SEGMENT_MAX_EVENTS=10000
ANALYTICS_EVENT_QUEUE_SEGMENT_MAX_AGE_MILLIS=1000
LIVE_SESSIONS_TYPE=disabled
```

The processor uses the same queue path and type. Its final storage binding is
independent of collector forwarding. Configure the existing Iceberg/catalog
settings on the processor. The API server or runner can also consume this queue
when `ANALYTICS_PROCESSOR_ENABLED=true`. An embedded consumer uses a separately
named final-storage binding so it does not enqueue events back into its own input.

The directory supports one producer and one consumer. They can share a process,
run on the same host, or share a persistent ReadWriteOnce volume in the same pod.
A second owner of either role fails rather than racing over the same files.
Separate nodes use NATS; a local directory is not shared cluster storage.

The producer appends framed collector batches to a `RandomAccessFile`, keeping
batch context once per frame. Frame lengths and payloads have separate checksums
so header corruption is detected before incomplete-tail recovery. Each enqueue returns after syncing its appended
bytes. A segment rolls after 10,000 events or one second by default; an idle
segment also rolls when its age limit expires. The settings above control both
limits. Sealing writes checksummed counts, syncs the file, and atomically renames
it for the consumer, followed by directory sync. Consumers only read sealed
segments. Low-volume handoff includes the age limit and timer/poll scheduling;
the rollover timer checks at intervals up to one second.

One sealed segment is one delivery, retaining its original collector batches,
contexts and timestamps. The processor transforms the batches, writes final
storage and flushes it before acknowledging. Acknowledgment deletes the segment
and syncs the directory. A failed delivery or consumer restart replays the whole
retained segment. A retry delay is in memory and does not prevent processing other
files. Worker count bounds concurrency; there are no per-record leases or
checkpoint/compaction settings.

A restart recovers complete frames in an open segment and truncates an incomplete
final append. Damaged segments are preserved for inspection. A crash after final
storage commits but before acknowledgment can replay the entire segment, so
transforms and sinks must tolerate duplicates. The disk backend does not
maintain a client deduplication index. Segment data and quarantined files count
toward the configured byte limit.

The capacity limit rejects additional enqueue attempts rather than deleting
pending events. Existing accepted files can still drain after lowering the byte
limit. Checksum-invalid segments are retained with a `.quarantine` suffix and
logged; transient read errors leave their original files available for retry. Inspect and repair/replay quarantined data
explicitly. Do not remove the spool directory while it contains pending work.
The data framing remains version 3. Retained files from the earlier checkpointed
implementation are read as whole segments and can replay previously processed
batches. Its old checkpoint and deduplication metadata are no longer read.
Disk persistence protects process/pod restarts only while the backing volume
survives; volume loss loses that local spool.

Collector readiness probes check the selected queue independently of incoming
traffic. A queue failure uses the existing readiness/liveness grace period.
Processors log queue status every minute, including the backend's available
backlog, age, quarantine, and capacity. Pending counts come directly from sealed
segment footers and include files currently being processed. Active, unsealed
files contribute bytes immediately and enter the pending count when they roll.
There is no separate in-flight or retry counter. Live-session maps remain an
optional NATS/Redis feature. They are disabled when `LIVE_SESSIONS_TYPE` is
empty and `PUBSUB_TYPE=postgres`, and `LIVE_SESSIONS_TYPE=disabled` states that
choice explicitly.

## Changing an existing installation

Selecting a backend does not transfer retained state. For an installation with
existing NATS chat history or queued jobs, plan an explicit export/import or a
drained cutover before changing selectors. Message sequences and reply/read
references must be preserved if history is transferred. Keep all processes on
matched configuration and binaries. Do not run producers against different
backends for the same workload during cutover.

Back up PostgreSQL and persistent analytics volumes through the installation's
existing operations procedures. Measure database load, queue latency, spool growth,
and recovery time with representative workloads before selecting production
capacity. No throughput guarantee follows from selecting a backend.
