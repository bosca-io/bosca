# GraphQL server benchmarks

The benchmark source set uses `kotlinx-benchmark` with JMH on the JVM. Separate parser, execution-engine, validator,
and concurrent-load workloads keep HTTP, database, and network work from obscuring regressions in each layer.

Run commands from the `bosca-workspace` root:

```bash
# Fast compilation and regression check: one fork, 1 warm-up, 2 × 250 ms measurements.
./gradlew :bosca-graphql:bosca-graphql-server:jvmBenchmarkSmokeBenchmark

# Stable latency profile: two forks, 5 warm-ups, 10 × 1 second measurements.
./gradlew :bosca-graphql:bosca-graphql-server:jvmBenchmarkBenchmark

# Parser-only latency profile: two forks, 5 warm-ups, 10 × 1 second measurements.
./gradlew :bosca-graphql:bosca-graphql-server:jvmBenchmarkParserBenchmark

# Shared-engine load profile: 64 JMH threads, two forks, throughput mode.
./gradlew :bosca-graphql:bosca-graphql-server:jvmBenchmarkLoadBenchmark
```

Each run writes a timestamped JMH-compatible JSON report under
`bosca-graphql/bosca-graphql-server/build/reports/benchmarks/<profile>/`.

The engine profile covers:

- coroutine-bridge overhead;
- a parsed scalar query;
- a 32-item nested response;
- a 32-item DataLoader response;
- uneven resolver latency under a concurrency limit; and
- a 1,000-field conflicting-selection validation case.

The parser profile measures source-to-AST latency independently for a tiny query, a representative operation with
variables, directives, and a fragment, 1,000-field and 60,000-field operations, and malformed-input rejection. This
keeps parser cost and permissive-default regressions visible without attributing validation or execution time to it.

The load profile exercises parser, cached-facade, and engine throughput across 64 callers. The parser workload shares
only immutable source text. The cached-facade workload prewarms one query and measures concurrent preparsed-cache hits
through the full `GraphQL.execute` pipeline. The engine workloads share immutable schemas, parsed documents, and
executors. The DataLoader benchmark also reuses one registry template, assigns every invocation a unique request
marker for the same key, and fails immediately if a value crosses request boundaries.

For ad hoc JMH profilers, first build the standalone jar:

```bash
./gradlew :bosca-graphql:bosca-graphql-server:jvmBenchmarkBenchmarkJar
java -jar bosca-graphql/bosca-graphql-server/build/benchmarks/jvmBenchmark/jars/bosca-graphql-server-jvmBenchmark-jmh-0.0.1-JMH.jar \
  GraphQLServerBenchmark.executeNestedResponse -prof gc

# Attribute parser allocation and collection costs separately from latency.
java -jar bosca-graphql/bosca-graphql-server/build/benchmarks/jvmBenchmark/jars/bosca-graphql-server-jvmBenchmark-jmh-0.0.1-JMH.jar \
  GraphQLParserBenchmark -wi 5 -i 10 -w 1s -r 1s -f 2 -tu us -bm avgt -prof gc
```

Run comparisons on an otherwise idle machine, with the same JDK, profile, and Gradle/JMH settings. Compare the JSON
confidence intervals rather than a single iteration.
