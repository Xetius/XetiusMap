val paperApiVersion = rootProject.property("paper_api_version") as String
val archivesBaseName = rootProject.property("archives_base_name") as String

base {
    archivesName.set("$archivesBaseName-server")
}

// Classes bundled verbatim into the plugin jar.
val shade: Configuration by configurations.creating

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    implementation(project(":common"))
    shade(project(":common"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":common"))
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
}

tasks.named<ProcessResources>("processResources") {
    val props = mapOf(
        "version" to project.version,
        "api_version" to paperApiVersion.substringBefore(".build."),
    )
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.named<Jar>("jar") {
    from({ shade.map { if (it.isDirectory) it else zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
}
