pluginManagement {
    // A property rather than a fixed version so the CodeQL workflow can compile with the newest Kotlin CodeQL
    // supports, which can lag behind the one the plugin is built with.
    val kotlinVersion = providers.gradleProperty("kotlinVersion").get()
    plugins {
        id("org.jetbrains.kotlin.jvm") version kotlinVersion
    }
}

rootProject.name = "sailpoint-intellij-plugin"
