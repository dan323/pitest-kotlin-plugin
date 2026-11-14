import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "io.github.dan323"
version = "0.1.0"

val pitestVersion = "1.30.2"

repositories {
    mavenCentral()
}

dependencies {
    // Provided by PIT at runtime; the plugin jar must not bundle it.
    compileOnly("org.pitest:pitest:$pitestVersion")
    compileOnly("org.pitest:pitest-entry:$pitestVersion")

    testImplementation("org.pitest:pitest:$pitestVersion")
    testImplementation("org.pitest:pitest-entry:$pitestVersion")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.withType<JavaCompile> {
    options.release = 17
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    // Central Portal. Credentials come from ORG_GRADLE_PROJECT_mavenCentralUsername / ...Password, never from this file.
    publishToMavenCentral()

    // Sign only when a key is supplied, so `publishToMavenLocal` works without one.
    if (providers.gradleProperty("signingInMemoryKey").isPresent || providers.gradleProperty("signing.keyId").isPresent) {
        signAllPublications()
    }

    coordinates("io.github.dan323", "pitest-kotlin-plugin", version.toString())

    pom {
        name = "PIT Kotlin plugin"
        description = "Filters noisy mutants and adds Kotlin-specific mutators (elvis, safe call, stdlib swaps) for PIT mutation testing."
        inceptionYear = "2026"
        url = "https://github.com/dan323/pitest-kotlin-plugin"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "dan323"
                name = "dan323"
                url = "https://github.com/dan323"
            }
        }
        scm {
            url = "https://github.com/dan323/pitest-kotlin-plugin"
            connection = "scm:git:git://github.com/dan323/pitest-kotlin-plugin.git"
            developerConnection = "scm:git:ssh://git@github.com/dan323/pitest-kotlin-plugin.git"
        }
    }
}
