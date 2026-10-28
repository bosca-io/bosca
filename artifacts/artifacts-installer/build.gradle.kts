plugins { id("org.jetbrains.kotlin.jvm") }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
dependencies {
    implementation(project(":bosca-core:core"))
    implementation(project(":artifacts:core-artifacts"))
    implementation(project(":artifacts:artifacts-base"))
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":pipelines:pipelines"))
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
