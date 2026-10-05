# Netty HTTP server benchmarks

The `jvmBenchmark` source set uses `kotlinx-benchmark` with JMH. Requests travel over loopback TCP through the same
HTTP codec, keep-alive, idle-timeout, WebSocket-upgrade, compression, chunked-write, routing, coroutine, request-body,
and response pipeline used by `NettyServerEngine` in production.

Run commands from the `bosca-workspace` root:

```bash
# Fast compilation and regression check: one fork, 1 warm-up, 2 × 250 ms measurements.
./gradlew :bosca-core:core:jvmBenchmarkSmokeBenchmark

# Stable single-connection latency profile: two forks, 5 warm-ups, 10 × 1 second measurements.
./gradlew :bosca-core:core:jvmBenchmarkBenchmark

# 64-connection throughput profile: two forks, 3 warm-ups, 5 × 1 second measurements.
./gradlew :bosca-core:core:jvmBenchmarkLoadBenchmark

# Focused WebSocket latency and 64-session throughput profiles with the same stable settings.
./gradlew :bosca-core:core:jvmBenchmarkWebsocketBenchmark
./gradlew :bosca-core:core:jvmBenchmarkWebsocketLoadBenchmark
```

Each run writes a timestamped JMH-compatible JSON report under
`bosca-core/core/build/reports/benchmarks/<profile>/`.

The latency profile measures:

- a 13-byte plain-text response on a persistent connection;
- the same response through a pre-encoded Bosca route and a minimal raw-Netty control;
- literal and parameterized route resolution with a query string;
- a 1 KiB request body echoed as an incompressible response;
- a 13-byte text-frame echo over one persistent RFC 6455 connection and one persistent RFC 8441 stream;
- a compressible 32 KiB JSON response requested with gzip support, as both a full and four-chunk streaming response;
  and
- a 32 KiB `application/octet-stream` response requested with gzip support that exercises the synchronous
  incompressible-content pass-through.

The load profile runs the HTTP/1 plain-text, request-body, full and streaming compressed-response, and incompressible
response cases over 64 independent
persistent connections. It also runs HTTP/2 plain text over 64 concurrent streams on one persistent connection and
over four persistent connections. WebSocket load cases run 64 persistent RFC 6455 connections and 64 persistent
RFC 8441 streams multiplexed over either one or four HTTP/2 connections. Minimal Netty RFC 6455 and independent
transport-only RFC 8441 echo servers use the same clients as the production route to isolate Bosca's application
overhead. The reported throughput includes the in-process client and loopback network stack,
so it is a conservative whole-server measurement rather than an isolated handler microbenchmark. It does not include
TLS or a production ingress proxy.

## Reference comparison

The following measurements were collected on 2026-08-04 and 2026-08-05 on an Apple M4 Max (16 cores, 128 GB),
macOS 26.6, and GraalVM JDK 25.0.3. Every snapshot used the `load` profile documented above on the same machine. The
baseline predates the routing, request-allocation, compression fast-path, and request-dispatcher changes that
accompanied this benchmark suite. The executor-locality snapshot additionally keeps the compressor, chunked writer,
and HTTP handler on the same per-channel codec executor and starts the non-suspending prefix of ordinary request
dispatch without another coroutine queue hop. The channel-local continuation snapshot also resumes ordinary HTTP
coroutines on the codec executor assigned to their channel after a suspension.

| 64-connection workload | Baseline | Benchmark-suite snapshot | Executor locality | Channel-local continuation | Change from executor locality |
| --- | ---: | ---: | ---: | ---: | ---: |
| Plain text | 50,078 ± 2,714 ops/s | 55,507 ± 1,650 ops/s | 79,392 ± 2,308 ops/s | 80,352 ± 2,313 ops/s | +1.21% |
| POST 1 KiB | 48,981 ± 2,054 ops/s | 52,776 ± 2,641 ops/s | 67,490 ± 2,475 ops/s | 80,284 ± 1,446 ops/s | +18.96% |
| Gzip 32 KiB | 39,884 ± 2,875 ops/s | 40,464 ± 4,657 ops/s | 77,015 ± 4,183 ops/s | 75,831 ± 4,057 ops/s | −1.54% |

The POST confidence interval is clear of the executor-locality snapshot; the plaintext and gzip intervals overlap and
should be treated as unchanged. Both changes preserve the production rule that compression stays off the Netty I/O
event loop. WebSocket upgrades remove the offloaded HTTP-only handlers before either streaming-handshake message can
be queued, then restore the WebSocket handler to the event loop.

A same-machine Vert.x Web 5.1.0 control, using the same JMH client and 64-connection profile, measured
135,283 ± 1,475 ops/s for its plaintext route. The channel-local Bosca snapshot is 59.4% of that throughput (a 1.68×
gap). This is a useful ceiling but not a framework-equivalent request path: the Vert.x handler writes one prebuilt
`Buffer` with explicit headers, while the Bosca case traverses its production middleware, routing, request/call/
response abstractions, coroutine lifecycle, string encoding, compression pass-through, and chunked-writer pipeline.
A raw-Netty and pre-encoded Bosca control should be kept beside future comparisons to divide transport cost from
Bosca abstraction cost.

Those controls are now part of both benchmark profiles. In the stable single-connection profile, that optimized
server measured 34.441 ± 0.242 µs/op for ordinary Bosca plaintext, 34.890 ± 0.398 µs/op for a pre-encoded Bosca body,
and 19.413 ± 0.202 µs/op for minimal Netty using the same HTTP codec and client. Pre-encoding does not improve the
Bosca path; the remaining approximately 15 µs is in the pipeline and request/call/response lifecycle. In the
64-connection profile, Bosca, pre-encoded Bosca, raw Netty, and the Vert.x control all converge around 136–138k
ops/s, demonstrating that the in-process load client is saturated at that point rather than establishing equal
server capacity.

A matched 64-connection JFR run showed `NioIoHandler.wakeup` falling from 22.27% to 2.45% of execution samples. The
remaining profile is dominated by executor queue submission, loopback socket work, and per-request coroutine/context
allocation; GC remains negligible (10.1 ms of pauses across the profiled run). An event-loop-only control reached
84,106 ± 5,084 ops/s, but is intentionally not used because it would put compression work back on the I/O loop.

A 2026-08-06 HTTP/1 regression run after the HTTP/2 and exchange-lifecycle changes measured
35.330 ± 0.796 µs/op for plaintext and 136,052 ± 2,819 ops/s under 64-connection load. Those results overlap the
post-sequencer latency snapshot and the 4-worker/16-codec throughput snapshot above; the later protocol work did not
produce a measurable HTTP/1 regression. POST and gzip throughput were too noisy in that run for a stronger claim.

## HTTP/2 snapshot and layer isolation

The HTTP/2 benchmark uses cleartext prior knowledge, opens a new stream for every exchange, and exercises the same
per-stream pipeline as the production server. The raw-Netty controls use the same client, frame codec, stream-channel
lifecycle, and response body. Measurements below were collected on 2026-08-06 on the reference host above.

The stable single-stream measurements locate the HTTP/2 latency gap:

| HTTP/2 plaintext path | Average latency |
| --- | ---: |
| Minimal raw Netty on the I/O event loop | 48.928 ± 0.619 µs/op |
| Bosca's compressor/chunked handlers on the I/O event loop, raw handler | 52.328 ± 4.959 µs/op |
| Bosca's compressor/chunked pipeline on `codecGroup`, raw handler | 74.130 ± 5.622 µs/op |
| Complete Bosca route | 74.967 ± 0.646 µs/op |

The event-loop pipeline overlaps the minimal raw control, while moving the pipeline to `codecGroup` adds about 22 µs.
The complete Bosca route overlaps the offloaded raw pipeline. For this non-suspending route,
`CoroutineStart.UNDISPATCHED` means routing, `ServerRequest`, `ServerCall`, the handler coroutine, and response creation
add less than the resolution of this whole-server comparison. The primary latency target is therefore the executor
boundary around the whole pipeline, not `Dispatchers.Default` or route resolution.

This did not mean compression should move onto the I/O event loop. (Where application work runs is a separate choice;
see "Coroutine dispatcher decision" below.) At the time of that snapshot the pipeline assigned
the compressor, chunked writer, and HTTP handler to `codecGroup`, so even a request with no `Accept-Encoding` header
paid an event-loop-to-codec handoff and a codec-to-event-loop write handoff. Selective compression offload keeps
request negotiation, chunked writing, and compression pass-through on the channel event loop, submits eligible
response bytes and their encoder state to one ordered codec executor, and runs route and middleware work wherever
`bosca.server.request-dispatcher` places it. A later pass-through response joins the ordered compression path while compression is pending, so
it cannot overtake the compressed response.

The four-connection load control reinforces the scheduling result. With four I/O workers, Bosca measured
84,078 ± 1,663 ops/s with 16 codec threads and 126,582 ± 2,863 ops/s with four codec threads. Minimal raw Netty reached
175,034 ± 3,422 ops/s. The codec-thread comparison is diagnostic evidence of handoff/cache contention, not a claim
that thread tuning alone closes the remaining gap. A one-connection control reached 55,905 ± 841 ops/s because all
streams on that connection are pinned to one I/O event loop.

The application layer remains a secondary allocation target. Under the same four-connection GC profile, the
offloaded raw pipeline allocated 12,282 ± 21 B/request and complete Bosca allocated 14,956 ± 35 B/request, a delta of
about 2.67 KiB/request. Both recorded only 14 ms of GC pauses across their measurement iterations, so those allocations
are not the present throughput limiter. JFR showed the common HTTP/2 stream-channel and Netty promise lifecycle as the
dominant CPU and allocation path.

## WebSocket snapshot and layer isolation

The focused WebSocket profiles use a real loopback TCP connection, complete the RFC 6455 Upgrade or RFC 8441 extended
CONNECT handshake during warm-up, and keep that connection and WebSocket session open for every measurement. Each
invocation sends a masked 13-byte text frame and verifies the unmasked echo byte for byte. The RFC 8441 load cases keep
64 WebSocket streams open concurrently on one or four persistent HTTP/2 parent connections. The control servers use
Netty's production frame codecs but omit Bosca routing, middleware, coroutine dispatch, and WebSocket session
abstractions. Measurements below were collected on 2026-08-07 on the reference host above; every row completed both
forks without a failed exchange or a non-terminating transport thread.

| WebSocket path | Average latency | 64-session throughput |
| --- | ---: | ---: |
| Bosca RFC 6455 | 32.897 ± 0.161 µs/op | 130,275 ± 3,543 ops/s |
| Minimal Netty RFC 6455 control | 19.437 ± 0.090 µs/op | 137,724 ± 2,478 ops/s |
| Bosca RFC 8441, one HTTP/2 parent | 48.510 ± 1.413 µs/op | 83,886 ± 1,115 ops/s |
| RFC 8441 transport control, one HTTP/2 parent | 34.340 ± 0.215 µs/op | 186,647 ± 7,968 ops/s |
| Bosca RFC 8441, four HTTP/2 parents | — | 99,942 ± 15,492 ops/s |
| RFC 8441 transport control, four HTTP/2 parents | — | 211,872 ± 6,586 ops/s |

Bosca's RFC 6455 route adds about 13.46 µs to the minimal transport path and sustains 94.6% of the control's measured
throughput. RFC 8441 adds about 14.17 µs at single-session latency, but under 64-session load the complete Bosca path
reaches 44.9% of the one-parent control and 47.2% of the four-parent control. That throughput gap is an application-
layer optimization target; the transport-only server is deliberately not framework-equivalent. Because RFC 8441 had
no earlier benchmark, these figures establish its initial regression baseline rather than claiming improvement or
regression against an unmeasured version.

## Selective compression offload event-loop experiment

The selective-offload experiment was measured on 2026-08-06 with the same reference host, JDK, and
4-worker/16-codec defaults. The stable latency profile used two forks, five one-second warmups, and ten one-second
measurements per fork. The load profile used 64 concurrent clients; the longer HTTP/2 confirmation used two forks,
five warmups, and ten measurements. This snapshot also ran ordinary route continuations on the channel event loop.
That dispatcher choice was rejected at the time because synchronous routing, GraphQL execution, middleware, and
serialization could stall unrelated sockets. later made event-loop dispatch the default, with blocking
calls moved to `Dispatchers.IO` (see "Event-loop dispatch" below); these older figures predate that code and are not
current performance claims.

| Workload | Before selective offload | After selective offload | Change |
| --- | ---: | ---: | ---: |
| HTTP/1 plain-text latency | 35.330 ± 0.796 µs/op | 20.615 ± 0.207 µs/op | −41.6% |
| HTTP/1 full gzip 32 KiB latency | 163.717 ± 4.371 µs/op | 136.828 ± 2.077 µs/op | −16.4% |
| HTTP/2 plain-text latency | 74.967 ± 0.646 µs/op | 54.685 ± 3.592 µs/op | −27.1% |
| HTTP/1 plain text, 64 connections | 136,052 ± 2,819 ops/s | 142,801 ± 5,897 ops/s | +5.0% |
| HTTP/1 full gzip 32 KiB, 64 connections | 86,216 ± 2,251 ops/s | 86,569 ± 1,535 ops/s | +0.4% |
| HTTP/2 plain text, 64 streams / 4 connections | 84,078 ± 1,663 ops/s | 128,644 ± 5,190 ops/s | +53.0% |

The HTTP/1 throughput intervals overlap, so those two throughput rows should be treated as unchanged. In this
experiment, the latency reductions and HTTP/2 throughput increase do not overlap their earlier confidence intervals. HTTP/1 plain text was
within about 1.2 µs of the earlier 19.413 ± 0.202 µs raw-Netty control. HTTP/2 overlapped the earlier
52.328 ± 4.959 µs event-loop Bosca-handler control, confirming the layer-isolation prediction that the whole-pipeline
executor boundary was the dominant avoidable cost.

The newly added workload controls measured:

| New workload | Latency | 64-connection throughput |
| --- | ---: | ---: |
| Four-chunk streaming gzip 32 KiB | 164.855 ± 6.196 µs/op | 58,405 ± 1,588 ops/s |
| Incompressible 32 KiB requested with gzip | 28.223 ± 0.229 µs/op | 122,507 ± 1,424 ops/s |

The incompressible result verifies that MIME-based compression rejection stays on the synchronous event-loop path.
The four-chunk streaming response is about 28 µs slower than the equivalent full response, consistent with the three
additional ordered compression submissions. That isolates streaming handoff frequency as a future optimization
candidate; it is not evidence that compression itself should return to the I/O loop.

## Coroutine dispatcher decision

Request handlers (ordinary routes, SSE, and WebSocket sessions) run where `bosca.server.request-dispatcher` says:

- `event-loop` (the default since 2026-09-25): on the connection's own Netty event loop, through
  `ChannelExecutorDispatcher` (`isDispatchNeeded = !inEventLoop`). A handler starts without a queue hop and writes
  its response without one; a continuation arriving from another dispatcher (a database or `Dispatchers.IO` call
  finishing) is queued back onto the loop. This is the same mechanism as Ktor's `NettyDispatcher` with
  `shareWorkGroup = true`. See "Event-loop dispatch" below.
- `pool`: on the dedicated `bosca-request` pool, sized like `Dispatchers.Default`'s core pool
  (`max(2, availableProcessors)`, `bosca.server.request-threads`; see "Request pool sizing" below), with one handoff
  into the pool and one back to the loop per request.

Until 2026-09-25 handlers ran on `Dispatchers.Default`, which the measurements in the next paragraph used. The
earlier event-loop snapshot above predates the current code and is superseded by the measurements below.

A matched POST profile on that version showed `NioIoHandler.wakeup` at 17.18% of execution samples and
`SingleThreadEventExecutor.execute` at 12.19%; `DefaultDispatcher` workers accounted for more than half of sampled
allocation. Replacing the application dispatcher with the shared codec group was not sufficient: it reduced a stable
POST control from 68,485 ± 3,913 to 61,542 ± 1,156 ops/s because the coroutine could resume on a different codec
thread. Resuming on the channel's assigned executor instead produced 79,342 ± 984 ops/s in the same control profile.
The experimental follow-up JFR contained no `DefaultDispatcher` worker allocation, and
`SingleThreadEventExecutor.execute` fell to 1.30% of execution samples. At the time, event-loop dispatch was rejected in
favor of isolating application work from the I/O loops. revisited that tradeoff with the measurements
in "Event-loop dispatch" below and made the event loop the default, with blocking calls moved to `Dispatchers.IO`.

### Request pool sizing

Measured 2026-09-25 on the reference host with `-XX:ActiveProcessorCount=2` to size every pool like the production pod.
That flag changes what the JVM reports, not the 16 physical cores the process may use, so CPU-bound absolute figures
are higher than a real two-CPU pod would reach; thread-count effects such as blocking are representative. The
`NettyHttpServerBlockingLoadBenchmark` cases add a route that holds its request thread for 10 ms and a `mixed` group
that runs 32 such clients beside 32 plain-text clients.

| 64-connection load (ops/s) | `Dispatchers.Default` | `bosca-request`, 2 threads | 4 threads | 16 threads | 64 threads |
| --- | ---: | ---: | ---: | ---: | ---: |
| HTTP/1 plain text | 139,996 ± 1,385 | 140,429 ± 1,539 | 134,467 ± 1,667 | 134,661 ± 1,505 | 132,708 ± 2,153 |
| POST 1 KiB | 137,828 ± 3,886 | 138,994 ± 2,019 | 130,689 ± 1,750 | 130,723 ± 2,117 | 127,708 ± 2,907 |
| RFC 6455 text echo | 138,577 ± 2,290 | 139,923 ± 2,799 | 129,217 ± 1,684 | 126,653 ± 2,897 | 124,338 ± 1,841 |
| RFC 8441 text echo, four parents | 176,258 ± 1,743 | 193,604 ± 7,028 | 133,286 ± 5,375 | 114,226 ± 1,572 | 106,605 ± 3,941 |
| 10 ms blocking route | 182 ± 1 | 181 ± 1 | 365 ± 2 | 1,469 ± 10 | 5,326 ± 54 |
| Plain text beside 32 blocking clients | 187 ± 1 | 186 ± 2 | 398 ± 7 | 2,650 ± 35 | 133,181 ± 2,940 |

The 64-thread column comes from an earlier run in the same session, which also measured
`Dispatchers.IO.limitedParallelism(64)`: it was slower than the 64-thread `bosca-request` pool on every load row
and 4–10% slower on single-connection latency, so it is not listed.

A pool no larger than the processor count matches `Dispatchers.Default` and was faster for RFC 8441. Any pool larger
than the processor count costs 4–9% on HTTP/1 and RFC 6455 and 24–40% on RFC 8441. No single pool is both fast and
tolerant of blocking: throughput requires about one thread per processor, while protecting unrelated requests requires
more threads than there are concurrently blocked requests (plain text recovers only at 64 threads against 32
blockers). The request pool is therefore processor-sized, and blocking calls must move to `Dispatchers.IO` at their
call sites. A handler that blocks the request pool still stalls every request, exactly as it did on
`Dispatchers.Default`.

### Event-loop dispatch

Measured 2026-09-25 on the reference host with `-XX:ActiveProcessorCount=2`: the channel's event loop (two loops)
against the two-thread `bosca-request` pool, same session, same benchmarks.

| Workload | `bosca-request` pool | Event loop | Change |
| --- | ---: | ---: | ---: |
| HTTP/1 plain text latency | 31.5 ± 0.9 µs/op | 21.3 ± 0.2 µs/op | −32% |
| POST 1 KiB latency | 33.1 ± 0.6 µs/op | 23.4 ± 0.3 µs/op | −29% |
| HTTP/2 plain text latency | 60.4 ± 2.1 µs/op | 52.9 ± 0.9 µs/op | −12% |
| RFC 6455 text echo latency | 31.5 ± 0.4 µs/op | 18.3 ± 0.1 µs/op | −42% |
| RFC 8441 text echo latency | 45.8 ± 0.8 µs/op | 32.8 ± 0.4 µs/op | −28% |
| HTTP/1 plain text, 64 connections | 138,726 ± 1,154 ops/s | 147,182 ± 3,914 ops/s | +6% |
| POST 1 KiB, 64 connections | 136,455 ± 1,925 ops/s | 145,867 ± 2,799 ops/s | +7% |
| HTTP/2 plain text, 64 streams / 4 connections | 112,292 ± 12,569 ops/s | 138,422 ± 10,992 ops/s | +23% |
| RFC 6455, 64 connections | 138,223 ± 1,948 ops/s | 144,841 ± 4,378 ops/s | +5% |
| RFC 8441, 64 streams / 4 parents | 190,597 ± 4,655 ops/s | 217,128 ± 11,307 ops/s | +14% |
| 10 ms blocking route | 182 ± 1 ops/s | 180 ± 1 ops/s | unchanged |
| Plain text beside 32 blocking clients | 186 ± 2 ops/s | 189 ± 14 ops/s | unchanged |

The saving is the two handoffs per request, so it is fixed per request: large for WebSocket messages and small
in-memory requests, a small fraction of any request that waits on the database. Blocking and CPU-heavy handlers cost
the same share of capacity in both modes at this size, because both run on as many threads as processors. What
differs is that a blocked event loop also stops network I/O for its connections (response flushes, HTTP/2 control
frames, WebSocket pings, idle timers), and event loops are capped at `worker-threads` (four by default) while the pool
grows with the processor count.

Database work does not hold the loop: `ConnectionManager.useStatement` and `useReadOnlyStatement` switch to
`DatabaseDispatcher` before acquiring a connection, the pool waits on a coroutine `Semaphore` and `Channel`, and
release also runs there, so the loop serves other connections while a query or a connection wait is in progress.
Blocking calls must move to `Dispatchers.IO` at their call site. Two opt-in diagnostics support this mode:
`bosca.server.blocked-thread-watchdog.enabled` reports any event loop (or, in `pool` mode, the request pool) that
stays in one task longer than `bosca.server.blocked-thread-watchdog.threshold-ms` (default 100), with stack traces;
`bosca.server.management-port` serves `/api/v1/live` from its own event loop, so liveness still answers while a
request loop is busy: 200 while every loop keeps running tasks, and 503 naming the loop once one has been stuck longer
than `bosca.server.management-liveness.stall-timeout-ms` (default 2 s), so Kubernetes restarts a wedged server.

The next POST allocation experiment retains the incoming Netty chunks until the body is complete, allocates one
exactly sized byte array, copies each chunk directly into it, and releases every retained buffer in a `finally` block.
This avoids trusting `Content-Length`, avoids the extra copy performed by `ByteArrayOutputStream`, and works with
Netty's pooled direct buffers, whose NIO views do not expose a backing array. A matched JMH GC profile fell from
8,488 ± 40 to 6,480 ± 33 B/op (−23.7%, about 2.0 KiB per 1 KiB request). Throughput was statistically unchanged at
81,859 ± 3,957 ops/s.

The plaintext allocation profile identified two avoidable per-call structures: the content-type response path
populated a concurrent header map before copying it into Netty headers, and every matched route populated the generic
call-attributes map solely to pass its route template to tracing. Writing the generated content type directly to the
Netty response, storing the route template in a dedicated field, and lazily creating generic attributes reduced
plaintext allocation from 3,579 ± 55 to 3,071 ± 59 B/op (−14.2%, about 508 bytes/request). The matched external k6
run did not show a throughput improvement, so this is recorded as an allocation result rather than a throughput
claim.

## Executor sizing finding

The prior production defaults created twice as many NIO workers and codec workers as available processors. On the
16-core benchmark host that meant 32 threads in each group. A two-fork sweep found a pronounced oversubscription
penalty in the in-process load profile:

| NIO workers | Codec workers | Plain text | POST 1 KiB | Gzip 32 KiB |
| ---: | ---: | ---: | ---: | ---: |
| 32 | 32 | 81,626 ± 1,686 ops/s | 82,084 ± 1,780 ops/s | 75,831 ± 4,057 ops/s |
| 16 | 16 | 87,127 ± 4,088 ops/s | 88,692 ± 3,320 ops/s | — |
| 8 | 8 | 103,785 ± 1,971 ops/s | 107,300 ± 3,819 ops/s | — |
| 4 | 4 | 139,417 ± 2,256 ops/s | 135,650 ± 2,092 ops/s | — |
| 2 | 2 | 142,330 ± 1,684 ops/s | 141,555 ± 2,453 ops/s | 18,341 ± 147 ops/s |
| 1 | 1 | 126,488 ± 2,487 ops/s | 118,214 ± 2,510 ops/s | — |
| 4 | 16 | 136,704 ± 2,091 ops/s | 136,050 ± 1,895 ops/s | 86,216 ± 2,251 ops/s |

Two codec threads serialize too much compression work, while 32 NIO workers impose unnecessary scheduling and cache
cost. The asymmetric 4-worker/16-codec result is the best balanced candidate from this sweep: compared with 32/32 it
improves plaintext by 67.5%, POST by 65.7%, and gzip by 13.7%. Its plaintext result is statistically level with the
same-machine Vert.x control.

A separate JVM running the server and a k6 process driving 64 persistent connections confirmed that the result is not
an artifact of running the JMH client in the server JVM:

| Workload | 32 workers / 32 codec | 4 workers / 16 codec | Change |
| --- | ---: | ---: | ---: |
| Plain text | 111,036 req/s | 149,733 req/s | +34.9% |
| POST 1 KiB | 108,266 req/s | 146,435 req/s | +35.3% |
| Gzip 32 KiB | 63,131 req/s | 69,197 req/s | +9.6% |

All six measured runs completed with zero HTTP failures. A follow-up simulated the production Kubernetes allocation
by starting each JMH fork with `-XX:ActiveProcessorCount=2`. The live native server pod has a 1-CPU request, a 2-CPU
limit, and reports two available processors: its prior `2x` defaults therefore resolve to 4 workers and 4 codec
threads.

| Two-CPU simulation | Plain text | POST 1 KiB | Gzip 32 KiB |
| --- | ---: | ---: | ---: |
| 4 workers / 4 codec | 134,121 ± 2,476 ops/s | 134,262 ± 1,011 ops/s | 36,446 ± 345 ops/s |
| 1 worker / 2 codec | 109,150 ± 3,252 ops/s | 103,036 ± 3,529 ops/s | 18,450 ± 77 ops/s |
| 2 workers / 4 codec | 138,593 ± 853 ops/s | 134,983 ± 967 ops/s | 36,598 ± 107 ops/s |
| 1 worker / 4 codec | 72,199 ± 2,042 ops/s | 67,850 ± 3,603 ops/s | 30,213 ± 6,592 ops/s |

The originally proposed quarter-CPU worker rule would give the production pod one I/O worker and is rejected by this
control. Defaults now use one I/O worker per available processor capped at four, plus one codec thread per available
processor with a minimum of four. This produces the measured 2/4 configuration in the production pod and 4/16 on the
16-core benchmark host. Both remain configurable through `bosca.server.worker-threads` and
`bosca.server.codec-threads`.

The benchmark server accepts `BOSCA_BENCHMARK_WORKER_THREADS` and `BOSCA_BENCHMARK_CODEC_THREADS`; when unset, both
retain the production default. For example:

```bash
BOSCA_BENCHMARK_WORKER_THREADS=4 BOSCA_BENCHMARK_CODEC_THREADS=16 \
  ./gradlew :bosca-core:core:jvmBenchmarkLoadBenchmark
```

To repeat the separate-process check, build the benchmark jar, run the standalone server, and drive one of the
`plaintext`, `post1KiB`, or `gzip32KiB` workloads from another shell:

```bash
./gradlew :bosca-core:core:jvmBenchmarkBenchmarkJar
BOSCA_BENCHMARK_WORKER_THREADS=4 BOSCA_BENCHMARK_CODEC_THREADS=16 \
  java -cp bosca-core/core/build/benchmarks/jvmBenchmark/jars/core-jvmBenchmark-jmh-0.0.1-JMH.jar \
  bosca.server.netty.NettyHttpBenchmarkServerMain 9090

BOSCA_BENCHMARK_WORKLOAD=plaintext BOSCA_BENCHMARK_DURATION=20s \
  k6 run bosca-core/core/src/jvmBenchmark/resources/netty-http-load.js
```

The earlier benchmark-suite snapshot's single-connection average latency was statistically unchanged from its
baseline:

| Latency workload | Baseline | Optimized |
| --- | ---: | ---: |
| Plain text | 52.690 ± 1.161 µs/op | 53.910 ± 4.896 µs/op |
| Path parameter | 51.773 ± 0.333 µs/op | 52.545 ± 1.576 µs/op |
| POST 1 KiB | 58.212 ± 8.076 µs/op | 55.146 ± 6.486 µs/op |
| Gzip 32 KiB | 158.563 ± 1.942 µs/op | 163.717 ± 4.371 µs/op |

The JMH GC profiler measured plain-text allocation falling from 4,949.827 ± 203.811 B/op at baseline to
3,603.280 ± 60.234 B/op in the benchmark-suite snapshot and 3,565.330 ± 54.321 B/op after the executor-locality
change. Absolute loopback values are host-specific; retain the same machine, JDK, and profile when using these numbers
as a regression baseline.

For ad hoc JMH profilers, first build the standalone jar:

```bash
./gradlew :bosca-core:core:jvmBenchmarkBenchmarkJar
java -jar bosca-core/core/build/benchmarks/jvmBenchmark/jars/core-jvmBenchmark-jmh-0.0.1-JMH.jar \
  NettyHttpServerBenchmark.plaintext -prof gc
```

Run comparisons on an otherwise idle machine with the same JDK, power mode, CPU count, and JMH profile. Compare JSON
confidence intervals rather than individual iterations. For externally generated peak-load results, use a separate
process and a tool such as `wrk2` or `k6`; the JMH load profile is intended primarily for repeatable code-change
comparisons.

# Cache benchmarks

The `bosca.cache` benchmarks in the same `jvmBenchmark` source set measure the `ServiceCache` → `RequestCache` →
`RequestCacheSerializer` → `Cache` path. They run only under the `cache*` profiles because the backend benchmarks lease
Valkey and NATS from the shared test-resource services (`./gradlew :bosca-core:test-support:testResourcesUp`, or
started on demand through Docker).

```bash
# Fast compilation and regression check: one fork, 1 warm-up, 2 × 250 ms measurements (~2 minutes).
./gradlew :bosca-core:core:jvmBenchmarkCacheSmokeBenchmark

# Single-caller latency profile: one fork, 2 warm-ups, 5 × 1 second measurements (~6.5 minutes, 44 runs).
./gradlew :bosca-core:core:jvmBenchmarkCacheBenchmark

# 64-caller throughput profile: one fork, 2 warm-ups, 5 × 1 second measurements (~1.5 minutes).
./gradlew :bosca-core:core:jvmBenchmarkCacheLoadBenchmark
```

The cache profiles use one fork and fewer iterations than the Netty profiles. Backend calls take 0.2–14 ms and
serializer calls collect millions of samples per iteration, so more forks add minutes without meaningfully narrowing
the intervals. Every parameter value is a separate JMH run, so add `@Param` values sparingly.

The suite is split so each cost can be read on its own:

| Benchmark class | Backend | Isolates |
|---|---|---|
| `RequestCacheSerializerBenchmark` | none | The self-describing JSON envelope on every remote read and write, against `direct*` encoding with the concrete `KSerializer`. |
| `RequestCacheSerializerLookupBenchmark` | none | Decode cost with `SerializerCache` filled (`registered=true`, native image) or empty (`false`, JVM server, reflective lookup). |
| `ServiceCacheBenchmark` | in-process map | `RequestCache` bookkeeping plus serialization, with no network. `runBlockingControl` is the fixed harness cost. |
| `ServiceRequestBenchmark` | in-process map | Whole requests against a service wired like `MetadataServiceImpl` (eight prefix-invalidated composite-key caches plus a permission cache), with a `ConnectionManager` in context so write-backs and removals defer to `release()`. |
| `ServiceMutationBenchmark` | in-process map | A request that reads a 50-entity listing, invalidates `mutations` (1 or 50) of those entities as `removeFromCache` does, and reads the listing again. Compare with `ServiceRequestBenchmark.readListing50Twice`. |
| `ServiceBackendRequestBenchmark` / `ServiceBackendMutationBenchmark` | `redis`, `nats` | The service-shaped requests over a real backend, including the flush on `release()`. The backend holds 1,000 entities in each of the service's nine caches, so prefix removals scan realistically sized hashes and buckets. |
| `CacheBackendBenchmark` | `redis`, `nats` | Per-operation latency. `raw*` calls `Cache` directly (backend floor); `service*` goes through `ServiceCache` with a fresh `RequestCache` per operation, as each request does. |
| `CacheBackendLoadBenchmark` | `redis`, `nats` | Throughput with 64 concurrent callers. Redis contends for the production default pool of 50 connections. |

Parameters:

- `payload` — `permissionList` (five grants; the shape of the eleven `ServiceCache<UUID, List<EntityPermission>>`
  caches) and `document` (a ~1 KiB record).
- `mutations` — entities invalidated per request in `ServiceMutationBenchmark`.
- `registered` — see `RequestCacheSerializerLookupBenchmark`. Every other class pre-registers its payload types.
- `backend` — `redis` (Valkey, `RedisCacheManager`/`RedisCache` Lua scripts) or `nats` (`NatsCacheManager`/JetStream
  KeyValue), on `CacheBackendFixture`.

Naming: `firstTouch*` / `service*` build a new `RequestCache` per operation; `repeat*` reuse a primed one (second and
later reads in one request); `*MissAndWrite` read an absent key, resolve it, and flush the write-back. Backend miss keys
rotate through a bounded per-thread pool (`CacheMissKeys`), and each write-back is evicted after the invocation, outside
the measured time. Without that, NATS runs exceed the shared test accounts' 32 MB JetStream quota.

## Cache baseline

Recorded 2026-09-24 on an Apple M4 Max (16 cores), macOS 26.6, GraalVM JDK 25.0.3, with the `cache` and `cacheLoad`
profiles. Valkey 8 and NATS 2.12 run in Docker Desktop, so every backend round trip crosses the Docker VM; treat
backend figures as relative (Redis vs. NATS, `raw*` vs. `service*`, before vs. after) rather than as production
latency. The 50-key read and write rows include the concurrent `NatsCache.putBatch` change.

CPU-only (µs/op, lower is better):

| Operation | Bosca path | Direct `KSerializer` / control |
|---|---:|---:|
| Decode, five-grant permission list | 1.880 ± 0.069 | 1.060 ± 0.020 |
| Decode, ~1 KiB document | 1.923 ± 0.034 | 1.170 ± 0.045 |
| Encode, five-grant permission list | 1.085 ± 0.030 | 0.491 ± 0.008 |
| Encode, ~1 KiB document | 1.420 ± 0.018 | 0.682 ± 0.031 |
| Decode with `SerializerCache` empty (JVM) vs. filled (native) | 1.890 ± 0.073 | 1.805 ± 0.064 |
| `ServiceCache.get`, value already in this request's `RequestCache` | 0.050 ± 0.016 | 0.051 ± 0.001 (`runBlockingControl`) |
| `ServiceCache.get`, first touch in a request (in-memory backend) | 2.182 ± 0.119 | |
| `ServiceCache.get`, miss + resolve + write-back (in-memory backend) | 1.548 ± 0.051 | |
| `ServiceCache.getAll` of 50, first touch (in-memory backend) | 108.958 ± 1.062 | |
| `ServiceCache.getAll` of 50, miss + resolve + write-back (in-memory backend) | 71.395 ± 4.869 | |

Backend latency (µs/op, lower is better):

| Operation | Redis | NATS |
|---|---:|---:|
| `rawGet` | 239.7 ± 61.7 | 218.1 ± 65.0 |
| `serviceGet` | 252.5 ± 96.3 | 291.9 ± 54.3 |
| `rawGetBatch50` | 489.5 ± 262.6 | 1,150.9 ± 327.7 |
| `serviceGetAll50` | 689.1 ± 219.0 | 1,239.2 ± 121.9 |
| `rawPutBatch50` | 844.3 ± 157.5 | 1,117.0 ± 96.4 |
| `serviceGetMissAndWrite` | 474.5 ± 128.2 | 615.0 ± 198.6 |
| `serviceGetAll50MissAndWrite` | 1,323.4 ± 139.3 | 2,348.4 ± 150.9 |

Throughput with 64 callers (ops/s, higher is better):

| Operation | Redis | NATS |
|---|---:|---:|
| `rawGet` | 36,956 ± 1,797 | 53,395 ± 1,178 |
| `serviceGet` | 32,878 ± 11,915 | 49,727 ± 7,256 |
| `serviceGetAll50` | 5,118 ± 3,924 | 2,279 ± 960 |
| `serviceGetMissAndWrite` | 16,849 ± 6,748 | 25,241 ± 4,798 |

`NatsCache.putBatch` previously wrote one entry per round trip. Making it concurrent, measured with the same benchmark
jar apart from `NatsCache` (NATS, µs/op):

| Operation | Sequential | Concurrent |
|---|---:|---:|
| `rawPutBatch50` | 10,782.7 ± 4,107.6 | 1,139.9 ± 66.6 |
| `serviceGetAll50MissAndWrite` | 16,134.1 ± 5,185.0 | 2,432.7 ± 386.3 |
| `serviceGetMissAndWrite` | 599.6 ± 44.6 | 613.2 ± 80.6 |

Readings from this baseline:

- A single cached read is dominated by the backend round trip. Bosca's own CPU on a first-touch read is about 2 µs,
  and `serviceGet` is within the error of `rawGet` on both backends.
- NATS KeyValue has no batch operations, so `NatsCache` fans a batch out as one concurrent request per key, against
  one Lua call on Redis. Slower 50-key reads on NATS are expected (under 64 callers it completes fewer than half as
  many as Redis) and are not an optimization target; NATS leads on single-key reads.
- The self-describing envelope makes decoding about 1.7× and encoding about 2.1× the cost of the concrete
  serializer: roughly 0.8 µs per value decoded and 0.6–0.7 µs per value encoded. That is small beside a round trip,
  but it accounts for most of the ~109 µs of CPU in a 50-key first-touch read.
- Resolving serializers reflectively when `SerializerCache` is empty costs about 5% per decode, so pre-registration
  on the JVM is not worth pursuing for the cache.

## Service-shaped requests

Measured 2026-09-24 on the same host with the `cache` profile settings and `-prof gc`. Each operation is one request
against `BenchmarkEntityService` (wired like `MetadataServiceImpl`) with deferred flush on `ConnectionManager.release()`
and an in-memory backend, so the figures are CPU and allocation only.

Invalidation used to scale with the number of keys the request had already cached: every prefix removal walked the
request-local entries of every cache and built a prefix string per entry before checking its cache name. A 50-entity
invalidation spent 84% of its time there (JMH stack profiler). `RequestCache` now groups local entries by cache name
and caches each entry's prefix string on first use, keeping the same `startsWith` matching. `getBatch` also no longer
writes back values that it resolved only because a removal was pending; the flush applied that removal after its puts,
so those writes were deleted in the same flush. `get` already behaved this way.

| Request | Before | After | Allocated before → after |
|---|---:|---:|---:|
| Read a 50-entity listing, invalidate 50 of them, read it again | 3,801 ± 1,446 µs | 228 ± 6 µs | 22.6 MB → 0.93 MB |
| Read a 50-entity listing, invalidate 1, read it again | 704 ± 15 µs | 592 ± 26 µs | 2.6 MB → 1.9 MB |
| Read a 50-entity listing (five caches, 250 values) | 565 ± 28 µs | 553 ± 7 µs | 1.88 MB → 1.77 MB |
| Read one entity (five caches) | 12.3 ± 0.6 µs | 11.9 ± 0.3 µs | 39.6 KB → 38.6 KB |
| Empty request lifecycle | 0.25 ± 0.01 µs | 0.21 ± 0.00 µs | 1,960 B → 1,936 B |

The benchmark's resolvers return prebuilt values, so a value resolved after invalidation skips JSON decoding. That makes
the absolute after-figure for 50 invalidations lower than a plain listing read. Both runs share this, so the
before/after difference is `RequestCache` overhead alone. Real resolvers query the database.

## Single-pass request cache serialization

Measured 2026-09-24 on the same host (JVM), one fork, 3 warm-ups, 5 × 1 s, `-prof gc`. Before this change,
deserialization was about 85% of the CPU in `ServiceRequestBenchmark.readListing50`: parsing the stored string into a
`JsonElement` tree, decoding that tree, and a `Class.forName` plus serializer lookup on every decode.
`RequestCacheSerializerImpl` now reads and writes the unchanged `{className, collection?, data}` envelope in one pass
and resolves serializers once per class name. On any failure the reader re-reads through the tree-based path, which
alone decides the outcome, so error behaviour is unchanged. `RequestCacheSerializerImplTest` passes against both
implementations, including a byte-exact envelope test, so old and new servers can share cached entries.

| Benchmark | Before | After | Allocated before → after |
|---|---:|---:|---:|
| `serialize`, five-grant permission list | 1.183 ± 0.640 µs | 0.651 ± 0.015 µs | 5,544 B → 2,248 B |
| `serialize`, ~1 KiB document | 1.446 ± 0.027 µs | 0.686 ± 0.022 µs | 5,608 B → 1,392 B |
| `deserialize`, five-grant permission list | 1.910 ± 0.016 µs | 1.398 ± 0.059 µs | 6,120 B → 5,048 B |
| `deserialize`, ~1 KiB document | 1.940 ± 0.057 µs | 1.301 ± 0.019 µs | 7,216 B → 4,536 B |
| `ServiceRequestBenchmark.readEntity` | 12.927 ± 0.767 µs | 8.109 ± 0.110 µs | 38.6 KB → 31.3 KB |
| `ServiceRequestBenchmark.readListing50` | 570.366 ± 11.890 µs | 384.616 ± 3.994 µs | 1.77 MB → 1.41 MB |
| `ServiceMutationBenchmark` (1 invalidation) | 588.278 ± 8.329 µs | 424.983 ± 13.190 µs | 1.86 MB → 1.51 MB |
| `ServiceMutationBenchmark` (50 invalidations) | 259.435 ± 131.120 µs | 229.153 ± 2.547 µs | 0.93 MB → 0.93 MB |

The `direct*` rows, which do not use the changed code, moved by up to 12% between the two runs; treat smaller
differences as noise. These are JVM figures. In a native image the per-decode `Class.forName` was already a
build-time table lookup, so expect a smaller gain there from the serializer caching; the single-pass change applies to
both, but its native effect has not been measured.

## Redis eviction-channel publishes

The `RedisCache` scripts `PUBLISH` each written, removed, or expired key to `<cache>:evictions`. Only
`NearCacheRedisCache`, which is not enabled, subscribes, so every publish currently reaches no one. Measured
2026-09-24 against the shared Valkey 8 test service (no subscribers):

- Client-side JMH latency (production script vs. the same script with only its `PUBLISH` calls removed) showed no
  difference outside the error bars for `put`, a 50-entry `putBatch`, an exact remove, or a 50-key prefix removal; the
  Docker round trip dominates.
- Server-side time, from `INFO commandstats` `usec_per_call` over 2,000 `EVAL`s per variant, three passes with the
  order swapped: the 50-entry `putBatch` script took 73.3–73.9 µs with publishes and 62.3–63.0 µs without,
  about 0.21 µs per published key or ~15% of that script. Single-key `put` and exact remove differed by at most
  0.5 µs, within run-to-run variation.

Publishing is therefore a server-capacity cost rather than a latency cost. Dragonfly was not measured.

`RedisCacheScripts` now takes `publishEvictions`. `RedisCacheManager.register` builds its scripts with it off, since it
creates only `RedisCache`; the scripts then run without their `PUBLISH` lines, the variant measured above.
`NearCacheRedisCache` requires publishing scripts and rejects others at construction.

To reproduce the server-side figure, extract a script's text from `RedisCacheScripts`, create a copy without its
`redis.call('PUBLISH', …)` lines, and for each variant run `CONFIG RESETSTAT`, `valkey-cli -r 2000 EVAL "<script>" …`,
then read `cmdstat_eval` from `INFO commandstats`.

## Request cache flush round trips

Measured 2026-09-24 with `ServiceBackendRequestBenchmark` and `ServiceBackendMutationBenchmark` (one fork, 2 warm-ups,
5 × 1 s) against the shared Valkey 8 and NATS 2.12 services, so every round trip crosses Docker Desktop's VM.

The request waits for the flush: `ConnectionManager.release()` and commit run it. It used to issue one round trip per
pending removal and one per cache written, all sequentially, and `NatsCache` listed every key in the bucket for each
prefix removal. Invalidating one entity the way `MetadataServiceImpl.removeFromCache` does queues one exact and eight
prefix removals. Now `Cache.removeBatch` applies one cache's removals together: one Lua call on Redis, and a single
bucket listing with concurrent deletes on NATS. `RequestCache` writes every cache concurrently, then removes from
every cache concurrently, keeping writes before removals within each cache.

| Request (read a 50-entity listing, invalidate N, read again, flush) | Before | After |
|---|---:|---:|
| Redis, N = 50 | 86.78 ± 12.63 ms | 2.89 ± 0.27 ms |
| NATS, N = 50 | 1,420.37 ± 77.54 ms | 18.32 ± 3.01 ms |
| Redis, N = 1 | 6.48 ± 3.94 ms | 3.82 ± 0.52 ms |
| NATS, N = 1 | 28.57 ± 2.60 ms | 17.54 ± 2.17 ms |

Read-only requests were unchanged within error. The N = 50 case can be faster than N = 1 because after
invalidation the listing re-read resolves through the benchmark's free resolvers instead of the backend. On NATS the
remaining cost is mostly the single bucket key listing per flush, which still grows with bucket size.
