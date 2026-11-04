// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

// Static analysis: ktlint for formatting, detekt for code smells.
//
// Both run as their own command-line tool rather than as a Gradle plugin, so neither
// has to match the Kotlin that AGP ships. Each keeps a baseline of what the code
// already had when the tools came in, and only findings outside it fail the build.
// After cleaning up old findings, shrink the baseline: ./gradlew ktlintBaseline detektBaseline

val ktlint: Configuration by configurations.creating
val detekt: Configuration by configurations.creating

dependencies {
    ktlint(libs.ktlint.cli) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        }
    }
    detekt(libs.detekt.cli)
}

val ktlintBaselinePath = "config/ktlint/baseline.xml"
val ktlintSources = listOf(
    "app/src/**/*.kt",
    "**/*.gradle.kts",
    "!**/build/**",
    "--baseline=$ktlintBaselinePath"
)
val ktlintBaselineFile: File = file(ktlintBaselinePath)

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Checks Kotlin formatting; findings in config/ktlint/baseline.xml are ignored."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args(ktlintSources)
}

tasks.register<JavaExec>("ktlintBaseline") {
    group = "verification"
    description = "Rewrites config/ktlint/baseline.xml from the current findings."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args(ktlintSources)
    // ktlint writes the baseline only when the file is missing, and still exits
    // non-zero for the findings it just recorded.
    isIgnoreExitValue = true
    val baseline = ktlintBaselineFile
    doFirst { baseline.delete() }
}

// detekt refuses an --input path that does not exist, so only pass the source sets there are.
val detektInputs = listOf("app/src/main/java", "app/src/test/java", "app/src/androidTest/java")
    .filter { file(it).isDirectory }
    .joinToString(",")
val detektArgs = listOf(
    "--input",
    detektInputs,
    "--config",
    "config/detekt/detekt.yml",
    "--build-upon-default-config",
    "--baseline",
    "config/detekt/baseline.xml"
)

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Looks for code smells; findings in config/detekt/baseline.xml are ignored."
    classpath = detekt
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    args(detektArgs + listOf("--report", "html:build/reports/detekt.html"))
}

tasks.register<JavaExec>("detektBaseline") {
    group = "verification"
    description = "Rewrites config/detekt/baseline.xml from the current findings."
    classpath = detekt
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    args(detektArgs + "--create-baseline")
}
