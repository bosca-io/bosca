plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    jacoco
}

dependencies {
    implementation(kotlin("gradle-plugin"))
    implementation(libs.android.gradle.plugin)
    testImplementation(kotlin("test-junit"))
}

sourceSets.main {
    kotlin.srcDirs(
        "../bml/bml-gradle/src/main/kotlin",
        "../bosca-graphql/bosca-graphql-gradle/src/main/kotlin",
    )
    resources.srcDir("../bml/bml-gradle/src/main/resources")
}

gradlePlugin {
    plugins {
        create("bml") {
            id = "io.bosca.bml"
            implementationClass = "bosca.bml.gradle.BmlGradlePlugin"
        }
        create("boscaGraphql") {
            id = "io.bosca.graphql"
            implementationClass = "bosca.graphql.gradle.BoscaGraphqlPlugin"
        }
    }
}

tasks.processResources {
    val pluginVersion = System.getenv("PUBLISH_VERSION")?.removePrefix("v") ?: "0.0.1"
    inputs.property("bmlPluginVersion", pluginVersion)
    filesMatching("bosca/bml/gradle/bml-gradle-plugin.properties") {
        expand("pluginVersion" to pluginVersion)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(sourceSets["main"].output.classesDirs.files.map { directory ->
        fileTree(directory) { include("bosca/buildlogic/**") }
    })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.0".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "1.0".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
