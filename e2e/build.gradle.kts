import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    id("info.solidsoft.pitest") version "1.19.0"
}

repositories {
    mavenCentral()
    mavenLocal() // run `./gradlew publishToMavenLocal` in the parent project first
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // The plugin under test, as published by the parent project; kotlin-stdlib comes with it.
    pitest("io.github.dan323:pitest-kotlin-plugin:0.1.0")
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

tasks.withType<JavaCompile> { options.release = 17 }

tasks.test { useJUnitPlatform() }

pitest {
    pitestVersion = "1.30.2"
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("sample.*")
    outputFormats = setOf("XML")
    timestampedReports = false
    threads = 2
    // -Pfeatures="-FKTINLINE,-FKTINTRINSICS,-FKTCORO" turns the plugin's filters off for the baseline run.
    (findProperty("features") as String?)?.let { features = it.split(",") }
    reportDir = layout.buildDirectory.dir(findProperty("reportName") as String? ?: "reports/pitest")
}
