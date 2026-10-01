import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("org.graalvm.buildtools.native")
    alias(libs.plugins.kover)
    application
}

group = "io.bosca"
// Single source of truth for the build version. CI injects the pushed tag via
// the RELEASE_VERSION env var (see .bosca/pipelines/release.yaml); a local build
// can override with -Pbosca.version=…; otherwise it's a dev placeholder. This
// drives the Maven version, the macOS .pkg version (pkgVersion falls back to
// it), and the version baked into the binary for `--version` / the update check.
// Kept verbatim (no `v` stripping) so it matches the version string the release
// pipeline publishes to the registry, which the update check compares against.
version = System.getenv("RELEASE_VERSION")
    ?: (findProperty("bosca.version") as String?)
    ?: "0.0.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(project(":bosca-core:core"))

    // BML — .bml project compile
    implementation(project(":bml:bml-compiler"))

    // Bosca-native typed GraphQL client runtime. Generated operations (see the generateBoscaGraphqlClient
    // task below) compile against BoscaOperation / GraphQLJson / the transports from this module.
    implementation(project(":bosca-graphql:bosca-graphql-client"))

    // Shared KMP auth library (login/refresh/exchange/sign-out via BoscaAuth)
    implementation(project(":bosca-kmp:auth-shared"))

    // MCP server — force kotlin-logging 7.x to avoid broken GraalVM
    // substitutions in 8.x (Target_KLoggerFactory$Companion targets
    // a class in the wrong package)
    implementation(libs.mcp.sdk) {
        exclude(group = "io.github.oshai", module = "kotlin-logging")
    }
    implementation(libs.oshai.logging)

    // ACP agent — expose Kit to editor clients over JSON-RPC on stdio.
    implementation(libs.acp)

    // A2A server — expose Kit to other agents over HTTP JSON-RPC.
    implementation(libs.koog.a2a.server) {
        exclude(group = "io.github.oshai", module = "kotlin-logging")
    }
    implementation(libs.koog.a2a.transport.server.jsonrpc.http) {
        exclude(group = "io.github.oshai", module = "kotlin-logging")
    }
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sse)

    // CI/CD pipeline YAML parsing
    implementation(libs.snakeyaml)

    // HTTP transport for the typed GraphQL client + signed-URL file transfers (formerly pulled in
    // transitively via Apollo; now a first-class dependency).
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.okhttp.coroutines)

    // Shared
    implementation(libs.clikt)
    implementation(libs.tamboui.widgets)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver) // WebSocket loopback for the subscription transport test
}

// ---------------------------------------------------------------------------
// Bosca-native GraphQL client codegen.
//
// This invokes the GraphQL code generator directly as a JavaExec. The worker
// classpath uses the :bosca-graphql:bosca-graphql-client project dependency.
//
// The classpath is the main runtimeClasspath CONFIGURATION (dependencies only — not
// this module's own output), so the worker gets the generator + its transitive
// parser/serialization with correct JVM variant attributes, and there's no
// compileKotlin -> generate -> compileKotlin task cycle.
//
// Output goes to the bosca.graphql.gen package. Custom scalars carry explicit,
// GraalVM-native-safe serializers (UUID/DateTime via bosca.cli.api.scalars.*).
// ---------------------------------------------------------------------------
val boscaGraphqlOut = layout.buildDirectory.dir("generated/bosca-graphql/kotlin")
val generateBoscaGraphqlClient = tasks.register<JavaExec>("generateBoscaGraphqlClient") {
    group = "bosca graphql"
    description = "Generate the typed Bosca GraphQL client from src/main/graphql/*.graphql + schema.graphqls."
    classpath = configurations.getByName("runtimeClasspath")
    mainClass.set("bosca.graphql.codegen.cli.BoscaGraphqlCodegenCliKt")
    val schemaFile = layout.projectDirectory.file("src/main/graphql/schema.graphqls")
    val queriesDir = layout.projectDirectory.dir("src/main/graphql")
    val outputDirectory = boscaGraphqlOut
    inputs.file(schemaFile).withPropertyName("schema")
    inputs.dir(queriesDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("operations")
    outputs.dir(boscaGraphqlOut).withPropertyName("generated")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "generate",
            "--schema", schemaFile.asFile.absolutePath,
            "--queries", queriesDir.asFile.absolutePath,
            "--out", outputDirectory.get().asFile.absolutePath,
            "--package", "bosca.graphql.gen",
            // UUID/DateTime carry explicit (native-safe) serializers (bosca.cli.api.scalars.*), applied via
            // @file:UseSerializers; JSON maps to JsonElement and Upload to the client's multipart Upload type.
            "--scalar", "JSON:kotlinx.serialization.json.JsonElement",
            "--scalar", "Long:kotlin.Long",
            "--scalar", "UUID:kotlin.uuid.Uuid::bosca.cli.api.scalars.UuidSerializer",
            "--scalar", "DateTime:java.time.ZonedDateTime::bosca.cli.api.scalars.ZonedDateTimeSerializer",
            "--scalar", "Upload:bosca.graphql.client.Upload::bosca.graphql.client.UploadSerializer",
        )
    })
}
kotlin.sourceSets.getByName("main").kotlin.srcDir(boscaGraphqlOut)
tasks.named("compileKotlin") { dependsOn(generateBoscaGraphqlClient) }

application {
    mainClass.set("bosca.cli.MainKt")
    applicationName = "bosca"
}

// ---------------------------------------------------------------------------
// Bake the build version into a classpath resource so the running binary can
// report it (`bosca --version`, `bosca version`) and compare it against the
// latest published release for the update check. The file is generated under
// build/ (never committed) and produced before processResources so it lands in
// the runtime resources and the native image. It is registered for native-image
// in src/main/resources/META-INF/native-image/.../resource-config.json.
// ---------------------------------------------------------------------------
val generatedVersionDir = layout.buildDirectory.dir("generated/version")
val generateVersionResource = tasks.register("generateVersionResource") {
    val outFile = generatedVersionDir.map { it.file("bosca/cli/version.txt") }
    val versionValue = project.version.toString()
    inputs.property("version", versionValue)
    outputs.file(outFile)
    doLast {
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText(versionValue)
    }
}
sourceSets["main"].resources.srcDir(generatedVersionDir)
tasks.named("processResources") { dependsOn(generateVersionResource) }

afterEvaluate {
    configurations.matching { it.name.startsWith("nativeImageTest") }.configureEach {
        setExtendsFrom(emptyList())
    }
}

graalvmNative {
    testSupport.set(false)
    binaries {
        named("main") {
            imageName.set("bosca")
            mainClass.set("bosca.cli.MainKt")
            buildArgs.add("-J-Xmx16g")
            buildArgs.add("--no-fallback")
            buildArgs.add("--exclude-config")
            buildArgs.add(".*kotlin-compiler-embeddable.*\\.jar")
            buildArgs.add("^/META-INF/native-image/.*")
            if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
                buildArgs.add("--gc=G1")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// macOS code signing + notarization (Developer ID) for the native `bosca`
// binary.
//
// These tasks are OPT-IN: they are not wired into `build`/`nativeCompile`, so
// an ordinary build never needs signing credentials. They only register on
// macOS. Credentials are read from gradle properties (preferred for local,
// e.g. in ~/.gradle/gradle.properties) or environment variables (preferred for
// CI). See MACOS_SIGNING.md for the one-time certificate / notary setup.
//
//   ./gradlew signNativeMacos        # nativeCompile, then Developer ID sign
//   ./gradlew notarizeNativeMacos    # sign, zip, upload to Apple notary, wait
//   ./gradlew distNativeMacos        # sign + notarize + verify (full release)
//
// A bare CLI binary cannot be stapled (stapler only supports .app/.pkg/.dmg),
// so Gatekeeper verifies notarization online on first launch. See the docs for
// the .pkg path if you need an offline-stapled artifact.
// ---------------------------------------------------------------------------
if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
    val nativeBinary = layout.buildDirectory.file("native/nativeCompile/bosca")
    val notarizationZip = layout.buildDirectory.file("native/nativeCompile/bosca-notarize.zip")
    val entitlements = layout.projectDirectory.file("signing/entitlements.plist")

    // "Developer ID Application: Your Name (TEAMID)" — run
    // `security find-identity -v -p codesigning` to find the exact string.
    val signIdentity = providers.gradleProperty("bosca.macos.signIdentity")
        .orElse(providers.environmentVariable("MACOS_SIGN_IDENTITY"))

    // Notary credentials — app-specific-password route (Option B).
    //   • Local: a stored keychain profile created once with
    //     `xcrun notarytool store-credentials NAME` (the password lives in the
    //     keychain, never here).
    //   • CI: Apple ID + Team ID supplied by environment variables plus the
    //     app-specific password supplied ONLY via the MACOS_NOTARY_PASSWORD env
    //     var. notarytool dereferences it as `@env:…`, so the secret never
    //     lands in argv or any file.
    // An App Store Connect API key (Option A) is still accepted as a fallback.
    val notaryProfile = providers.gradleProperty("bosca.macos.notaryKeychainProfile")
        .orElse(providers.environmentVariable("MACOS_NOTARY_KEYCHAIN_PROFILE"))
    val notaryAppleId = providers.gradleProperty("bosca.macos.notaryAppleId")
        .orElse(providers.environmentVariable("MACOS_NOTARY_APPLE_ID"))
    val notaryTeamId = providers.gradleProperty("bosca.macos.notaryTeamId")
        .orElse(providers.environmentVariable("MACOS_NOTARY_TEAM_ID"))
    // Presence of this env var is what activates the direct Apple ID form; its
    // value is consumed by notarytool via `@env:`, not read into the build.
    val notaryPasswordEnv = providers.environmentVariable("MACOS_NOTARY_PASSWORD")
    val notaryApiKeyPath = providers.gradleProperty("bosca.macos.notaryApiKeyPath")
        .orElse(providers.environmentVariable("MACOS_NOTARY_KEY"))
    val notaryApiKeyId = providers.gradleProperty("bosca.macos.notaryApiKeyId")
        .orElse(providers.environmentVariable("MACOS_NOTARY_KEY_ID"))
    val notaryApiIssuer = providers.gradleProperty("bosca.macos.notaryApiIssuer")
        .orElse(providers.environmentVariable("MACOS_NOTARY_ISSUER"))

    // "Developer ID Installer: Your Name (TEAMID)" — a SEPARATE certificate
    // from the Application cert above, used only to sign the .pkg wrapper.
    val installerSignIdentity = providers.gradleProperty("bosca.macos.installerSignIdentity")
        .orElse(providers.environmentVariable("MACOS_INSTALLER_SIGN_IDENTITY"))
    val pkgIdentifier = providers.gradleProperty("bosca.macos.pkgIdentifier").orElse("io.bosca.cli")
    val pkgVersion = providers.gradleProperty("bosca.macos.pkgVersion").orElse(project.version.toString())

    // Notary auth shared by every notarytool call (zip and .pkg). Precedence:
    // direct Apple ID + app-specific password (CI) → stored keychain profile
    // (local) → API key. The Apple ID form wins only when its password env var
    // is actually set. Without that password, a configured keychain profile
    // is used before falling back to an API key.
    fun hasAppleIdCreds() = notaryAppleId.isPresent && notaryTeamId.isPresent && notaryPasswordEnv.isPresent
    fun hasApiKey() = notaryApiKeyPath.isPresent && notaryApiKeyId.isPresent && notaryApiIssuer.isPresent

    fun requireNotaryCreds() {
        require(hasAppleIdCreds() || notaryProfile.isPresent || hasApiKey()) {
            "Missing notary credentials. Provide ONE of:\n" +
                "  • a stored keychain profile (local): bosca.macos.notaryKeychainProfile=NAME " +
                "(create once with `xcrun notarytool store-credentials NAME`)\n" +
                "  • Apple ID + app-specific password (CI): bosca.macos.notaryAppleId + bosca.macos.notaryTeamId " +
                "+ the MACOS_NOTARY_PASSWORD env var\n" +
                "  • an App Store Connect API key trio: bosca.macos.notaryApiKeyPath/Id/Issuer\n" +
                "See MACOS_SIGNING.md for setup."
        }
    }

    fun notaryAuthArgs(): List<String> = when {
        hasAppleIdCreds() -> listOf(
            "--apple-id", notaryAppleId.get(),
            "--team-id", notaryTeamId.get(),
            // Read the secret from the env var at notarytool runtime so it
            // never enters this process's argument list.
            "--password", "@env:MACOS_NOTARY_PASSWORD",
        )
        notaryProfile.isPresent -> listOf("--keychain-profile", notaryProfile.get())
        else -> listOf(
            "--key", notaryApiKeyPath.get(),
            "--key-id", notaryApiKeyId.get(),
            "--issuer", notaryApiIssuer.get(),
        )
    }

    val signNative = tasks.register<Exec>("signNativeMacos") {
        group = "distribution"
        description = "Code-sign the native `bosca` binary with a Developer ID Application certificate (hardened runtime)."
        dependsOn("nativeCompile")
        inputs.file(nativeBinary)
        inputs.file(entitlements)
        // Signing happens in place and depends on credentials/clock, so never
        // treat it as up to date — always re-sign when asked.
        outputs.upToDateWhen { false }
        executable = "codesign"
        doFirst {
            require(signIdentity.isPresent) {
                "Missing signing identity. Set -Pbosca.macos.signIdentity=\"Developer ID Application: NAME (TEAMID)\" " +
                    "or export MACOS_SIGN_IDENTITY. List installed certs with: security find-identity -v -p codesigning"
            }
        }
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "--force",                              // replace the linker's ad-hoc signature
                "--options", "runtime",                 // hardened runtime (required for notarization)
                "--timestamp",                          // secure timestamp (required for notarization)
                "--entitlements", entitlements.asFile.absolutePath,
                "--sign", signIdentity.get(),
                nativeBinary.get().asFile.absolutePath,
            )
        })
    }

    // Apple's notary service only accepts containers (.zip/.pkg/.dmg), not a
    // bare Mach-O. `ditto` is Apple's recommended archiver for this.
    val packageZip = tasks.register<Exec>("packageNativeMacosZip") {
        group = "distribution"
        description = "Zip the signed `bosca` binary (via ditto) for notarization."
        dependsOn(signNative)
        inputs.file(nativeBinary)
        outputs.file(notarizationZip)
        executable = "ditto"
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "-c", "-k", "--keepParent",
                nativeBinary.get().asFile.absolutePath,
                notarizationZip.get().asFile.absolutePath,
            )
        })
    }

    val notarize = tasks.register<Exec>("notarizeNativeMacos") {
        group = "distribution"
        description = "Submit the signed `bosca` binary to Apple's notary service and wait for the verdict."
        dependsOn(packageZip)
        inputs.file(notarizationZip)
        outputs.upToDateWhen { false }
        executable = "xcrun"
        doFirst { requireNotaryCreds() }
        argumentProviders.add(CommandLineArgumentProvider {
            listOf("notarytool", "submit", notarizationZip.get().asFile.absolutePath, "--wait") + notaryAuthArgs()
        })
        doLast {
            logger.lifecycle(
                "Notarization complete. A bare CLI binary cannot be stapled, so Gatekeeper verifies " +
                    "online on first launch. Distribute build/native/nativeCompile/bosca (or the zip). " +
                    "For an offline-stapled artifact, build a .pkg/.dmg — see MACOS_SIGNING.md.",
            )
        }
    }

    val verify = tasks.register<Exec>("verifyNativeMacos") {
        group = "distribution"
        description = "Verify the `bosca` binary's Developer ID signature and hardened-runtime flags."
        mustRunAfter(signNative, notarize)
        inputs.file(nativeBinary)
        executable = "codesign"
        argumentProviders.add(CommandLineArgumentProvider {
            listOf("--verify", "--strict", "--verbose=2", nativeBinary.get().asFile.absolutePath)
        })
    }

    tasks.register("distNativeMacos") {
        group = "distribution"
        description = "Build, Developer ID sign, notarize, and verify the native `bosca` binary for macOS distribution."
        dependsOn(notarize, verify)
    }

    // -----------------------------------------------------------------------
    // Offline-stapleable .pkg installer path. Unlike a bare binary, a .pkg can
    // carry a stapled notarization ticket, so first launch succeeds with no
    // network. The .pkg installs `bosca` to /usr/local/bin. Needs a separate
    // "Developer ID Installer" certificate; notary credentials are shared.
    //
    //   ./gradlew distNativeMacosPkg     # sign + pkg + notarize + staple
    // -----------------------------------------------------------------------
    val pkgRoot = layout.buildDirectory.dir("native/pkgRoot")
    val pkgFile = layout.buildDirectory.file("native/nativeCompile/bosca.pkg")

    // pkgbuild packages a whole directory tree, so stage exactly one file: the
    // already-signed binary, with its executable bit, into an isolated root.
    val stagePkgRoot = tasks.register<Sync>("stageNativeMacosPkgRoot") {
        dependsOn(signNative)
        from(nativeBinary)
        into(pkgRoot)
        filePermissions { unix("755") }
    }

    val buildPkg = tasks.register<Exec>("packageNativeMacosPkg") {
        group = "distribution"
        description = "Build a Developer ID-signed .pkg installer that places `bosca` in /usr/local/bin."
        dependsOn(stagePkgRoot)
        inputs.dir(pkgRoot)
        inputs.property("identifier", pkgIdentifier)
        inputs.property("version", pkgVersion)
        outputs.file(pkgFile)
        executable = "pkgbuild"
        doFirst {
            require(installerSignIdentity.isPresent) {
                "Missing installer signing identity. Set -Pbosca.macos.installerSignIdentity=\"Developer ID Installer: NAME (TEAMID)\" " +
                    "or export MACOS_INSTALLER_SIGN_IDENTITY. This is a SEPARATE cert from the Application one; see MACOS_SIGNING.md."
            }
        }
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "--root", pkgRoot.get().asFile.absolutePath,
                "--identifier", pkgIdentifier.get(),
                "--version", pkgVersion.get(),
                "--install-location", "/usr/local/bin",
                "--sign", installerSignIdentity.get(),
                pkgFile.get().asFile.absolutePath,
            )
        })
    }

    val notarizePkg = tasks.register<Exec>("notarizeNativeMacosPkg") {
        group = "distribution"
        description = "Submit the .pkg installer to Apple's notary service and wait for the verdict."
        dependsOn(buildPkg)
        inputs.file(pkgFile)
        outputs.upToDateWhen { false }
        executable = "xcrun"
        doFirst { requireNotaryCreds() }
        argumentProviders.add(CommandLineArgumentProvider {
            listOf("notarytool", "submit", pkgFile.get().asFile.absolutePath, "--wait") + notaryAuthArgs()
        })
    }

    val staplePkg = tasks.register<Exec>("stapleNativeMacosPkg") {
        group = "distribution"
        description = "Staple the notarization ticket onto the .pkg installer for offline verification."
        dependsOn(notarizePkg)
        inputs.file(pkgFile)
        outputs.upToDateWhen { false }
        executable = "xcrun"
        argumentProviders.add(CommandLineArgumentProvider {
            listOf("stapler", "staple", pkgFile.get().asFile.absolutePath)
        })
    }

    val verifyPkg = tasks.register<Exec>("verifyNativeMacosPkg") {
        group = "distribution"
        description = "Assess the stapled .pkg the way Gatekeeper will (spctl install policy)."
        mustRunAfter(staplePkg)
        inputs.file(pkgFile)
        // Absolute path: spctl lives in /usr/sbin, which is often absent from a
        // CI agent's PATH (unlike /usr/bin where codesign/pkgbuild/xcrun live).
        executable = "/usr/sbin/spctl"
        argumentProviders.add(CommandLineArgumentProvider {
            listOf("--assess", "--verbose=4", "--type", "install", pkgFile.get().asFile.absolutePath)
        })
    }

    tasks.register("distNativeMacosPkg") {
        group = "distribution"
        description = "Build, sign, notarize, and staple a Developer ID .pkg installer for the native `bosca` binary."
        dependsOn(staplePkg, verifyPkg)
    }
}
