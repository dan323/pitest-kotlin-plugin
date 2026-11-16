pluginManagement {
    val kotlinVersion = providers.gradleProperty("kotlinVersion").getOrElse("2.4.21")
    plugins {
        kotlin("jvm") version kotlinVersion
    }
}

rootProject.name = "e2e-sample"
