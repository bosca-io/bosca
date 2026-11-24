# Analytics review fixes — 2026-11-14

Four correctness fixes retain the whole-segment queue design:

- Parquet record-write exceptions fail the append; failed writers cannot commit.
  Staging and queued segments remain available for retry.
- Staging uses a RAF and remembers the start of an incomplete frame. Before reuse
  or flush, it truncates that frame; a failed rollback prevents further writes
  until recovery succeeds. The existing length-prefixed staging format is unchanged.
- Collector queue initialization acquires the producer role and recovers active
  data before traffic. Consumers and status observers do not acquire that role.
- Decoded event/frame counts must match intact segment footers, including during
  interrupted-file recovery. Mismatches preserve the original bytes in quarantine.

Regression tests exercise failed writes and rollback, real queue-to-Iceberg
retry/commit, generated collector readiness without incoming events, and count
mismatches while healthy files continue draining.

Validation passed: **761 analytics tests and 6 collector tests**, no failures or
skips; **99.2472% line / 98.1557% branch** against unchanged 99%/98% gates.
Processor, API and runner Kotlin compilation and separate-process native queue
prepare/recovery passed. The updated generated collector test passed in a
subsequent collector-suite run.

## Local pipeline measurements

Both uninstrumented runs used GraalVM Java 25.0.3, a 1 GiB heap, local APFS,
production queue fsync, and an offered 10,000 healthy events/sec for 60 seconds.
An earlier 10,000-event segment kept retrying. Both runs committed all accepted
healthy IDs without loss or duplication, preserved the failed segment across
consumer restart, and reported zero quarantined files.

| Run | Healthy events | Producer events/sec | Handoff p99 | Iceberg flush p50 / p99 |
| --- | ---: | ---: | ---: | ---: |
| During collector validation | 600,100 | 10,001 | 4.383 s | 250.1 / 3,375.7 ms |
| After collector validation | 594,700 | 9,863 | 2.796 s | 213.1 / 1,924.7 ms |

The earlier failed segment retried 12 times in each run. This shared developer
host has no exclusive CPU or I/O control. Latency is variable and higher than the
previous report; these are not controlled before/after comparisons or deployment
capacity guarantees. The benchmark includes local protobuf staging, event-record
transform, Parquet and HadoopTables commits. It excludes HTTP, domain transforms,
the deployed catalog/object store and deployment volume. Those acceptance checks
remain open.

[Raw results and source hashes](2026-11-14-review-fixes.json). Reproduce using the
[benchmark instructions](README.md) with `--seconds=60 --rate=10000`.

Validation commands, from the workspace root:

```bash
./gradlew --continue :analytics:analytics:test :analytics:analytics:koverVerify \
  :analytics:analytics:koverXmlReport :analytics:analytics-collector:test \
  :analytics:analytics-processor:compileKotlin :server:bosca-server:compileKotlin \
  :server:bosca-runner:compileKotlin :analytics:analytics:nativeDiskAnalyticsQueueSmoke \
  :analytics:analytics:benchmarkClasses --no-build-cache
./gradlew :analytics:analytics-collector:test --no-build-cache
```

The benchmark main class ran directly with the Gradle-built benchmark runtime
classpath and the same JVM settings as the maintained Gradle benchmark task.
