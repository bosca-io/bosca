import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    // Settings plugins are resolved before Gradle creates the version catalog accessors.
    val intellijPlatformVersion = providers.fileContents(layout.settingsDirectory.file("gradle/libs.versions.toml"))
        .asText.get().lineSequence()
        .single { it.startsWith("intellij-platform = \"") }
        .substringAfter('"').substringBefore('"')
    plugins {
        id("org.jetbrains.intellij.platform.settings") version intellijPlatformVersion
    }
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
        val registryUrl = providers.gradleProperty("boscaRegistryUrl").orNull
        if (registryUrl != null) {
            maven {
                url = uri(registryUrl)
                credentials {
                    username = providers.gradleProperty("boscaRegistryUsername").orNull
                    password = providers.gradleProperty("boscaRegistryPassword").orNull
                }
            }
        }
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings")
}

rootProject.name = "bosca-workspace"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://maven.pkg.jetbrains.space/public/p/ktor/eap")
        val registryUrl = providers.gradleProperty("boscaRegistryUrl").orNull
        if (registryUrl != null) {
            maven {
                url = uri(registryUrl)
                content { includeGroupByRegex("io\\.bosca(\\..*)?") }
                credentials {
                    username = providers.gradleProperty("boscaRegistryUsername").orNull
                    password = providers.gradleProperty("boscaRegistryPassword").orNull
                }
            }
        }
    }
}

// The KMP client modules enable Android only when an SDK is available.
val androidProperties = java.util.Properties()
val androidPropertiesFile = file("bosca-kmp/local.properties")
if (androidPropertiesFile.exists()) {
    androidPropertiesFile.inputStream().use { androidProperties.load(it) }
}
val androidSdkDirectory = androidProperties.getProperty("sdk.dir")
    ?.takeIf { file(it).exists() }
    ?: (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))
        ?.takeIf { file(it).exists() }
    ?: listOf("Library/Android/sdk", "Android/Sdk", "AppData/Local/Android/Sdk")
        .map { "${System.getProperty("user.home")}/$it" }
        .firstOrNull { file(it).exists() }
    ?: listOf("/usr/local/share/android-sdk", "/opt/android-sdk")
        .firstOrNull { file(it).exists() }
gradle.extra.set("androidEnabled", androidSdkDirectory != null)

// IntelliJ supplies Kotlin's standard library for these plugin projects.
gradle.beforeProject {
    if (path == ":bml:bml-ide" || path == ":workops:workops-ide") {
        extensions.extraProperties.set("kotlin.stdlib.default.dependency", "false")
    }
}

fun Settings.includeComponent(dir: String, modules: List<String> = emptyList()) {
    val name = dir.substringAfterLast('/')
    include(":$name")
    project(":$name").projectDir = file(dir)
    modules.forEach { module ->
        include(":$name:$module")
        project(":$name:$module").projectDir = file("$dir/${module.replace(':', '/')}")
    }
}

includeComponent("services-di", listOf(
    "di",
    "di-ksp",
    "service",
    "service-ksp",
    "base-ksp",
))

includeComponent("bosca-yks", listOf(
    "yks",
))

includeComponent("bosca-core", listOf(
    "core",
    "core-annotations",
    "core-ksp",
    "core-security",
    "security",
    "core-storage",
    "storage",
    "core-scheduler",
    "scheduler",
    "core-events",
    "events",
    "core-forms",
    "forms",
    "core-configuration",
    "configuration",
    "core-devices",
    "devices",
    "core-graalvm",
    "test-support",
))

includeComponent("bosca-graphql", listOf(
    "bosca-graphql",
    "bosca-graphql-client",
    "bosca-graphql-server",
    "bosca-graphql-gradle",
))

includeComponent("bosca-kmp", listOf(
    "auth-shared",
    "analytics-core",
    "analytics-compiler",
    "analytics-gradle",
    "client-core",
    "dom-shared",
    "bible-dom-shared",
))

includeComponent("content", listOf(
    "core-content",
    "content",
    "core-languages",
    "languages",
    "core-localization",
    "localization",
    "slugs",
    "bible-compiler",
    "core-comments",
    "comments",
))

includeComponent("search", listOf(
    "core-search",
    "search",
))

includeComponent("social", listOf(
    "core-profile",
    "profile",
    "core-community",
    "community",
    "core-collaboration",
    "collaboration",
    "core-chat",
    "chat",
))

includeComponent("communications", listOf(
    "core-communications",
    "communications",
))

includeComponent("ai", listOf(
    "core-ai",
    "ai",
    "kit",
))

includeComponent("workops", listOf(
    "core-workops",
    "workops",
    "workops-jobs",
    "store-pipelines",
))

includeComponent("experimentation", listOf(
    "core-experimentation",
    "experimentation",
    "core-recommendations",
    "recommendations",
    "core-segmentation",
    "segmentation",
))

includeComponent("bml", listOf(
    "bml-annotations",
    "bml-compiler",
    "core-bml",
    "bml",
    "bml-server",
    "bml-benchmarks",
    "bml-message-host",
    "bml-message-server",
    "bml-message-client",
    "bml-gradle",
    "bml-sample",
    "examples:site",
))

includeComponent("calendar", listOf(
    "core-calendar",
    "calendar",
))

includeComponent("scripting", listOf(
    "core-scripting",
    "scripting",
    "scripting-engine",
))

includeComponent("pipelines", listOf(
    "core-pipelines",
    "pipelines",
))

includeComponent("ecommerce", listOf(
    "core-ecommerce",
    "ecommerce",
    "payment-bluepay",
    "payment-stripe",
    "shipping-shippo",
    "iap",
    "tax-avalara",
))

includeComponent("feeds", listOf(
    "core-feeds",
    "feeds",
))

includeComponent("git", listOf(
    "core-git",
    "core-git-ci",
    "git",
    "git-ci",
    "git-jobs",
    "git-server",
))

includeComponent("backup", listOf(
    "backup",
))

includeComponent("artifacts", listOf(
    "core-artifacts",
    "artifacts-base",
    "artifacts-admin",
    "artifacts-docker",
    "artifacts-helm",
    "artifacts-maven",
    "artifacts-npm",
    "artifacts-raw",
    "artifacts-ml",
    "artifacts-server",
))

includeComponent("analytics", listOf(
    "core-analytics",
    "analytics",
    "analytics-ai",
    "analytics-models",
    "analytics-server-client",
    "analytics-collector",
    "analytics-processor",
))

includeComponent("admin-support", listOf(
    "nats-admin",
    "postgres-admin",
    "meilisearch-admin",
    "diagnostics",
))

includeComponent("integrations", listOf(
    "hubspot",
    "meilisearch",
    "mux",
))

includeComponent("kubernetes", listOf(
    "core-kubernetes",
    "kubernetes",
    "kubernetes-controller",
    "kubernetes-pipelines",
))

includeComponent("gateway", listOf(
    "core-gateway",
    "gateway",
))

includeComponent("sharedqueue", listOf(
    "sharedqueue",
))

includeComponent("firebase-scrypt", listOf(
    "firebase-scrypt",
))

includeComponent("server", listOf(
    "bosca-server",
    "bosca-runner",
    "messages-pages",
))

includeComponent("cli")

includeComponent("apps/notifications-web")

includeComponent("apps/profiles-web")

includeComponent("apps/bosca-messages")

includeComponent("apps/client-core-ui", listOf("client-core-ui"))

include(":infra", ":infra:scripts-host")
project(":infra").projectDir = file("infra")
project(":infra:scripts-host").projectDir = file("infra/support/scripts-host")

include(":bml:bml-ide")
project(":bml:bml-ide").projectDir = file("bml/bml-ide")
include(":workops:workops-ide")
project(":workops:workops-ide").projectDir = file("workops/workops-ide")
