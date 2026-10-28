import com.google.devtools.ksp.gradle.KspAATask
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
        
    }
}

dependencies {
    api(project(":backup:core-backup"))

    implementation(project(":bosca-core:core"))
    implementation(project(":content:core-content"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":pipelines:core-pipelines"))
    testImplementation(project(":content:content"))
    implementation(project(":bosca-core:storage"))

    implementation(project(":sharedqueue:sharedqueue"))

    implementation(libs.postgresql)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(project(":bosca-core:security"))
    testImplementation(project(":bosca-core:configuration"))
    testImplementation(project(":bosca-core:core-configuration"))
    testImplementation(project(":social:core-profile"))
    testImplementation(project(":social:profile"))
    testImplementation(project(":content:core-comments"))
    testImplementation(project(":pipelines:pipelines"))
}

ksp {
    arg("ProviderRegistrarPrefix", "Backup")
}

// Test registrars must not shadow the production providers exercised by integration tests.
tasks.withType<KspAATask>().configureEach {
    if (name == "kspTestKotlin") {
        commandLineArgumentProviders.add(CommandLineArgumentProvider {
            listOf("ProviderRegistrarPrefix=BackupTest")
        })
    }
}

// The hard-interruption integration test launches a separate capture JVM.
tasks.withType<Test>().configureEach {
    val workerClasspath = classpath
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dbosca.backup.testClasspath=${workerClasspath.asPath}")
    })
}
