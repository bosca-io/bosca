plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

dependencies {
    compileOnly(kotlin("gradle-plugin-api"))
    implementation(kotlin("gradle-plugin"))
    testImplementation(gradleTestKit())
    testImplementation(libs.kotlin.test.junit)
}

gradlePlugin {
    plugins {
        create("analytics") {
            id = "io.bosca.analytics"
            implementationClass = "bosca.analytics.gradle.AnalyticsGradlePlugin"
        }
    }
}

tasks.processResources {
    val analyticsPluginVersion = project.version.toString()
    inputs.property("analyticsPluginVersion", analyticsPluginVersion)
    filesMatching("bosca/analytics/gradle/analytics-gradle-plugin.properties") {
        expand("pluginVersion" to analyticsPluginVersion)
    }
}
