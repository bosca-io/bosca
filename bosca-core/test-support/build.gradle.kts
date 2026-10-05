plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    api(project(":bosca-core:core"))
    api(libs.testcontainers)
    implementation(libs.postgresql)
    implementation(libs.nats)

    testImplementation(libs.kotlin.test)
}

tasks.register<JavaExec>("testResourcesUp") {
    group = "verification"
    description = "Start the shared PostgreSQL, NATS, Valkey, and Meilisearch integration-test services"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("bosca.test.resources.SharedTestResourcesKt")
    args("up")
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("testResourcesDown") {
    group = "verification"
    description = "Stop the shared PostgreSQL, NATS, Valkey, and Meilisearch integration-test services"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("bosca.test.resources.SharedTestResourcesKt")
    args("down")
    outputs.upToDateWhen { false }
}
