import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.sailpoint"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    jvmToolchain(25)
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
}

// Feed for a custom plugin repository: an IDE that adds its URL offers each new release as a plugin update.
// The release workflow publishes it to GitHub Pages next to the zip it attaches to the GitHub Release.
tasks.register("updatePluginsXml") {
    group = "distribution"
    description = "Writes updatePlugins.xml, the custom plugin repository feed for this version."
    val version = providers.gradleProperty("pluginVersion")
    val sinceBuild = providers.gradleProperty("pluginSinceBuild")
    val releaseUrl = providers.gradleProperty("pluginReleaseUrl")
    val zipName = rootProject.name
    val output = layout.buildDirectory.file("distributions/updatePlugins.xml")
    inputs.property("version", version)
    inputs.property("sinceBuild", sinceBuild)
    inputs.property("releaseUrl", releaseUrl)
    outputs.file(output)
    doLast {
        val v = version.get()
        output.get().asFile.writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<plugins>
            |  <plugin id="com.sailpoint.intellij" url="${releaseUrl.get()}/v$v/$zipName-$v.zip" version="$v">
            |    <idea-version since-build="${sinceBuild.get()}"/>
            |    <name>SailPoint Identity Security Cloud</name>
            |    <vendor>SailPoint</vendor>
            |    <description>Work with SailPoint Identity Security Cloud from the IDE.</description>
            |  </plugin>
            |</plugins>
            |""".trimMargin(),
        )
    }
}
