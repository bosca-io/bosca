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
    implementation(libs.caffeine)
    implementation(project(":bml:bml-message-client")) // the BML Message Server's typed render client
    implementation(project(":social:core-profile"))
    implementation(project(":social:profile"))
    implementation(project(":bosca-core:core-devices"))
    implementation(project(":content:core-content"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":bosca-core:core-configuration"))
    implementation(project(":communications:core-communications"))
    implementation(project(":sharedqueue:sharedqueue"))
    // The @JobDefinition KSP generator emits a pipeline-aware enqueue(context, nodeId) overload that
    // references core-pipelines (PipelineContext), so every job-defining module needs it on the classpath.
    implementation(project(":pipelines:core-pipelines"))

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.firebaseadmin)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bcpkix.jdk18on)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.testcontainers)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(libs.testcontainers.postgresql)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
}

ksp {
    arg("ProviderRegistrarPrefix", "Communications")
}

kover {
    reports {
        // Gate the complete delivery-status path: provider routing and HTTP handling, durable
        // status jobs, persistence, notification visibility, and GraphQL paging resolvers.
        // Generated repositories and services outside this delivery path remain outside the gate.
        filters {
            includes {
                classes(
                    "bosca.communications.jobs.MessageJob",
                    "bosca.communications.jobs.RecordDeliveryEventsJobExecutor",
                    "bosca.communications.mailers.mailgun.MailgunMailer",
                    "bosca.communications.mailers.mailgun.MailgunMessage",
                    "bosca.communications.mailers.sendgrid.SendGridMailer",
                    "bosca.communications.mailers.sendgrid.SendGridMessage",
                    "bosca.communications.routes.SendGridWebhook",
                    "bosca.communications.service.MessageServiceImpl",
                    "bosca.communications.service.DeliveryTrackingServiceImpl",
                    "bosca.communications.service.NotificationTypeServiceImpl",
                    "bosca.communications.graphql.DeliveryStatusesController",
                    "bosca.communications.graphql.RecipientDeliveryStatusController",
                )
            }
            excludes {
                annotatedBy("bosca.di.annotation.Generated")
            }
        }
        verify {
            rule("Line coverage") { minBound(100) }
            rule("Branch coverage") {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 92
                }
            }
        }
    }
}
