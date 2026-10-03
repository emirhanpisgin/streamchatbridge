import org.gradle.api.tasks.compile.JavaCompile

plugins {
    // This plugin applies the correct loom variant based on the Minecraft version
    id("dev.kikugie.loom-back-compat")
}

// DO NOT set group = ...!
version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = "${property("mod.id") as String}-fabric"

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "1.17" -> JavaVersion.VERSION_17
    else -> JavaVersion.VERSION_1_8
}

dependencies {
    /**
     * Fetches only the required Fabric API modules to not waste time downloading all of them for each version.
     * @see <a href="https://github.com/FabricMC/fabric">List of Fabric API modules</a>
     */
    fun fapi(vararg modules: String) {
        for (it in modules) modImplementation(fabricApi.module(it, sc.properties["deps.fabric_api"]))
    }

    minecraft("com.mojang:minecraft:${sc.current.version}")
    // Applies Mojang Mappings on obfuscated versions
    loomx.applyMojangMappings()

    // Use `mod{dependency type}` even on 26.1+ - loom-back-compat converts them
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    fapi("fabric-lifecycle-events-v1")
    if (sc.current.parsed >= "26.1") fapi("fabric-key-mapping-api-v1")
    else fapi("fabric-key-binding-api-v1")
    if (sc.current.parsed >= "1.19") fapi("fabric-command-api-v2")
    else fapi("fabric-command-api-v1")
}

loom {
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json") // Useful for interface injection

    runConfigs.all {
        preferGradleTask = true
        generateRunConfig = true
        runDirectory = rootProject.file("run") // Shares the run directory between versions

        // Test hook: `-PscbQuickPlay=<worldFolder>` jumps straight into a world.
        if (project.hasProperty("scbQuickPlay")) {
            programArgs("--quickPlaySingleplayer", project.property("scbQuickPlay") as String)
        }
    }
}

java {
    withSourcesJar()
}

// Compile with the current JDK but target the Java version each Minecraft version requires.
// A toolchain would force foojay to download a JDK, which failed on this machine.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(requiredJava.majorVersion.toInt())
}

tasks {
    processResources {
        fun MutableMap<String, String>.register(key: String, property: String) {
            val value: String = sc.properties[property]
            inputs.property(key, value)
            set(key, value)
        }

        val props = buildMap {
            register("id", "mod.id")
            register("name", "mod.name")
            register("version", "mod.version")
            register("minecraft", "mod.mc_compat")
            register("command_api", "deps.fabric_command_api")
            register("pack_format", "pack_format")
            val keyModule = if (sc.current.parsed >= "26.1") "fabric-key-mapping-api-v1" else "fabric-key-binding-api-v1"
            inputs.property("key_module", keyModule)
            set("key_module", keyModule)
            set("java", requiredJava.majorVersion)
        }

        filesMatching("fabric.mod.json") { expand(props) }
        filesMatching("pack.mcmeta") { expand(props) }

        exclude("META-INF/mods.toml", "META-INF/neoforge.mods.toml")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to `build/libs/{mod version}/`"

        inputs.property("version", project.property("mod.version"))
        // loomx.mod(Sources)Jar returns the jar task for the applied loom variant
        from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}

