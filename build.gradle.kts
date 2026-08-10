plugins {
    java
}

val javaVersion = (property("java_version") as String).toInt()

allprojects {
    group = rootProject.property("maven_group") as String
    version = rootProject.property("mod_version") as String
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(javaVersion)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
