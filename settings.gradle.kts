pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version").get() apply false
    }
}

rootProject.name = "XetiusMap"

include("common")
include("fabric")
include("paper")
