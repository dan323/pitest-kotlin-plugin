pluginManagement {
    // The Kotlin version the fixtures and the plugin are compiled with: -PkotlinVersion=2.2.20 (CI runs a matrix).
    val kotlinVersion = providers.gradleProperty("kotlinVersion").getOrElse("2.4.21")
    plugins {
        kotlin("jvm") version kotlinVersion
    }
}

rootProject.name = "pitest-kotlin-plugin"
