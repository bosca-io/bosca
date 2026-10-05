# BML benchmarks

The `bml-benchmarks` project measures a real compiler-generated page and the production BML HTTP
routes, including concurrent request throughput. It also measures compile time separately. It uses
JMH through the same `kotlinx-benchmark` plugin as Bosca Core.
The benchmark fixture is a 24-item page with escaped interpolations and a shared-cache policy.

Run from the **workspace root** with Java 25:

```bash
./gradlew :bml:bml-benchmarks:jvmBenchmarkSmokeBenchmark
./gradlew :bml:bml-benchmarks:jvmBenchmarkBenchmark
./gradlew :bml:bml-benchmarks:jvmBenchmarkLoadSmokeBenchmark
./gradlew :bml:bml-benchmarks:jvmBenchmarkLoadBenchmark
```

To profile allocation per HTTP request, build the standalone JMH jar and run its GC profiler:

```bash
./gradlew :bml:bml-benchmarks:jvmBenchmarkBenchmarkJar
java -jar bml/bml-benchmarks/build/benchmarks/jvmBenchmark/jars/*-JMH.jar \
  'BmlHttpLoadBenchmark\.(staticControl|privatePage|sharedPage)' \
  -prof gc -f 1 -wi 3 -i 5 -w 1s -r 1s
```

The smoke tasks verify that every benchmark starts and completes. For comparisons, the full latency
task runs five warmups and ten measured iterations in each of two JVM forks. The full load task uses
three warmups and five measured iterations in each of two forks, with 64 concurrent callers per
operation. Latency results are average microseconds per operation; load results are operations per
second. Keep the JDK, machine, power settings, and benchmark configuration the same when comparing
revisions, and compare full runs rather than smoke numbers. Each run writes JSON under
`bml/bml-benchmarks/build/reports/benchmarks/<profile>/<timestamp>/jvmBenchmark.json`.
The task fails if JMH cannot produce a fresh report for every scenario.

| Benchmark | What one operation includes |
| --- | --- |
| `BmlCompilerBenchmark.parsePage` | Parse a representative BML source into its syntax tree. |
| `BmlCompilerBenchmark.generatePage` | Generate Kotlin from an already parsed BML page. |
| `BmlRenderBenchmark.renderCompiledPage` | Allocate a render context and render the compiled 24-item page. |
| `BmlRenderBenchmark.encodeDeferredProps` | Encode typed deferred props for a shared page shell. |
| `BmlRenderBenchmark.decodeDeferredRequest` | Parse and decode a private deferred-render POST body. |
| `BmlHttpBenchmark.privatePage` | Loopback HTTP response for the compiled page under a private route. |
| `BmlHttpBenchmark.sharedPage` | Loopback HTTP 200 response for the compiled shared page. |
| `BmlHttpBenchmark.sharedPageNotModified` | Loopback conditional HTTP 304 using a public-data revision. |
| `BmlHttpBenchmark.deferredFragment` | Loopback POST through the private deferred-render route. |
| `BmlHttpBenchmark.staticControl` | Static response through the same Bosca HTTP engine, with the compiled page's HTML rendered once before measurement. |
| `BmlHttpLoadBenchmark.*` | Concurrent requests for the static control, private and shared pages, and deferred fragments. |

The HTTP latency measurements include the JDK client, loopback network, Netty, routing, and BML
server handling. The load profile uses 64 persistent HTTP/1.1 connections, one per benchmark thread,
and includes the loopback network, response framing, and server handling. Compare BML routes with
the static control in the same run to estimate the server and client floor. These measurements
exclude real GraphQL calls and an edge cache. The compiler measurements are build costs; they do
not run during a page request. JMH runs separately from `test`, so normal test runs do not depend
on timing. Establish a baseline on the machine used for performance comparisons before adding a
regression threshold.

## Runtime baseline

The full `load` profile on 2026-09-24 used macOS 26.6 and GraalVM JDK 25.0.3, with 64 persistent
HTTP/1.1 connections, two forks, three one-second warmups, and five one-second measurements per
fork. The first run preceded direct HTML escaping and replacing per-byte digest formatting with
`HexFormat`; the current run includes both changes. These are loopback results from this
workstation, not production capacity estimates.

| Route | Before (ops/s, 99.9% interval) | Current (ops/s, 99.9% interval) |
| --- | ---: | ---: |
| Static HTML control through Bosca HTTP | 128,330 ± 7,564 | 130,323 ± 1,206 |
| Compiled BML private page | 120,672 ± 5,237 | 126,492 ± 2,428 |
| Compiled BML shared page | 115,116 ± 2,371 | 127,206 ± 2,486 |
| BML deferred fragment | 111,858 ± 7,309 | 117,133 ± 5,585 |

The shared-page intervals do not overlap, while the static-control intervals do. The control
returns the compiled page's HTML bytes without per-request BML rendering. A separate one-fork JMH
GC profile, using the command above, measured these allocations:

| Route | Before (bytes/request) | Current (bytes/request) |
| --- | ---: | ---: |
| Static HTML control | 3,780 | 3,779 |
| Private BML page | 23,859 | 21,164 |
| Shared BML page | 37,623 | 24,738 |

Run BML routes and the control together when comparing revisions; machine load and the loopback
client affect every score. No regression threshold is set until a target and repeated same-machine
baseline are agreed on.
