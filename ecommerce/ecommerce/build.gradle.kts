plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
        
    }
}

dependencies {
    implementation(project(":bosca-core:core"))
    implementation(project(":bosca-core:core-security"))
    // @JobDefinition KSP emits a pipeline-aware enqueue(context, nodeId) → needs core-pipelines
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":ecommerce:core-ecommerce"))

    // Products are content Metadata-backed; companies are organization profiles;
    // products are searchable through the platform search infra. Cross-module access
    // is via core-* contracts only (no impl -> impl).
    implementation(project(":content:core-content"))
    implementation(project(":social:core-profile"))
    implementation(project(":search:core-search"))

    // Domain events register in the platform event catalog and dispatch via @JobEvent.
    implementation(project(":bosca-core:core-events"))

    // Background jobs (cart expiration, subscription renewals, fulfillment sync) run on bosca-runner.
    implementation(project(":sharedqueue:sharedqueue"))

    // Periodic jobs are registered as cron-scheduled jobs via the platform scheduler + installer.
    implementation(project(":bosca-core:core-scheduler"))

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(libs.testcontainers.postgresql)
}

ksp {
    arg("ProviderRegistrarPrefix", "Ecommerce")
}

kover {
    reports {
        filters {
            excludes {
                // Only genuinely GENERATED code is excluded — never whole hand-written classes.
                // All KSP-generated code (repository impls, DI providers, registrars, generated
                // serializers, resolver wiring) is emitted with @bosca.di.annotation.Generated;
                // exclude it by that annotation (exact, not name-glob). We deliberately do NOT
                // exclude @Serializable classes wholesale — those can hold real logic that must be
                // tested. The compiler-generated equals/hashCode/serializer in the event+job DTOs is
                // instead COVERED by real tests (equality, serialization round-trip, and
                // malformed-input deserialize) — see EcomEvents*Test / JobPayloadSerializationTest.
                annotatedBy("bosca.di.annotation.Generated")
            }
        }
        // Enforce the coverage bar on hand-written code (only KSP-generated code is excluded above).
        // Achieved at the time of writing: line 99.9%, branch 98.7%. The branch figure was lifted from
        // ~97% by rewriting elvis/safe-call-on-non-null idioms (`x?.nonNullProp ?: default`) — which the
        // compiler lowers to a branch that is provably unreachable yet still counted — into explicit
        // `if (x != null) x.prop else default` forms that are fully testable, plus targeted null-edge
        // tests. The remaining residual is NOT per-branch excludable by Kover and is not worth distorting
        // code to chase: compiler-generated equals/serializer arms in the @Serializable event/job DTOs,
        // a couple of irreducible safe-call/`as?`-on-a-sole-subtype synthetics, dead-but-intentional
        // defensive guards (e.g. submit()'s `!alreadyPaid`, which payableCartForUpdate already
        // guarantees), and one random-input Luhn arm. Thresholds sit just below the achieved values so
        // the build fails on a real regression while tolerating minor codegen variance across compiler
        // versions.
        verify {
            rule("Hand-written line coverage") {
                minBound(99)
            }
            rule("Hand-written branch coverage") {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 98
                }
            }
        }
    }
}
