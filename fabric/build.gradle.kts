plugins {
    id("net.fabricmc.fabric-loom")
}

val minecraftVersion = rootProject.property("minecraft_version") as String
val loaderVersion = rootProject.property("loader_version") as String
val fabricApiVersion = rootProject.property("fabric_api_version") as String
val archivesBaseName = rootProject.property("archives_base_name") as String

base {
    archivesName.set("$archivesBaseName-client")
}

loom {
    runs {
        named("client") {
            // Loom 1.17 marks the whole programArg family deprecated without offering a
            // replacement yet, so the warning is suppressed rather than worked around.
            @Suppress("DEPRECATION")
            programArgs("--username", "XetiusDev")
            // ./gradlew :fabric:runClient -PquickPlay=localhost:25565 joins a server on launch,
            // which is the quickest way to exercise the shared-map path against a live plugin.
            (project.findProperty("quickPlay") as String?)?.let {
                @Suppress("DEPRECATION")
                programArgs("--quickPlayMultiplayer", it)
            }
        }
    }
}

// Classes bundled verbatim into the mod jar. :common carries no Minecraft references.
val shade: Configuration by configurations.creating

dependencies {
    // Minecraft 26.x ships unobfuscated, so there is no `mappings` line and no remap step:
    // loader and Fabric API come in as plain `implementation` dependencies.
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    implementation(project(":common"))
    shade(project(":common"))
}

tasks.named<ProcessResources>("processResources") {
    val props = mapOf(
        "version" to project.version,
        "minecraft_version" to minecraftVersion,
        "loader_version" to loaderVersion,
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

tasks.named<Jar>("jar") {
    from({ shade.map { if (it.isDirectory) it else zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
}
