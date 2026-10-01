import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("java")
    id("net.neoforged.moddev") version "2.0.147"
    id("neoforge-mutex")
}

version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = "${property("mod.id") as String}-neoforge"
val modId = property("mod.id") as String

// Same rationale as the Forge script: fail fast when the session JDK doesn't
// match what this node expects. ModDevGradle manages its own compile/recompile
// JVMs via toolchains, so most NeoForge nodes need no constraint at all.
val expectedJdk: String? = try { sc.properties["session_jdk"] } catch (_: Exception) { null }
if (expectedJdk != null) {
    val current = JavaVersion.current().majorVersion.toInt()
    val required = expectedJdk.toInt()
    if (current != required) {
        throw GradleException(
            "Project ':${project.name}' must be built on Java $required, but this Gradle daemon runs Java $current.\n" +
            "Stop daemons (`gradlew --stop`) and re-run with JAVA_HOME set to a Java $required installation."
        )
    }
}

neoForge {
    // The full NeoForge artifact version, e.g. "21.1.247".
    version = property("deps.neo_loader") as String

    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
    }

    runs {
        create("client") {
            client()
        }
        create("server") {
            server()
        }
    }
}

repositories {
    mavenCentral()
}

java {
    withSourcesJar()
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
            register("pack_format", "pack_format")
        }

        // NeoForge 20.4 (FML 2.x) still reads META-INF/mods.toml; the rename to
        // neoforge.mods.toml happened with NeoForge 20.5 (FML 3.x).
        val legacyModsToml = sc.current.parsed < "1.20.5"
        inputs.property("legacyModsToml", legacyModsToml)
        filesMatching("META-INF/neoforge.mods.toml") {
            expand(props)
            if (legacyModsToml) name = "mods.toml"
        }
        filesMatching("pack.mcmeta") { expand(props) }

        exclude("fabric.mod.json", "META-INF/mods.toml")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to `build/libs/{mod version}/`"

        inputs.property("version", project.property("mod.version"))
        from(jar.flatMap { it.archiveFile }, named<Jar>("sourcesJar").flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}
