pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
    }
}

plugins {
    // Check the latest version on https://stonecutter.kikugie.dev/blog/changes/0.9
    id("dev.kikugie.stonecutter") version "0.9.7"

    // Cross-compat for 26.1+ and older versions (https://codeberg.org/KikuGie/loom-back-compat)
    id("dev.kikugie.loom-back-compat") version "0.4.2"

    // Auto-downloads missing JDK toolchains (https://github.com/gradle/foojay-toolchains)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        /**
         * Creates version nodes for multiple loaders.
         *
         * This function will create subprojects named `versions/{project}-{loader}`.
         * Each project has a logical [version], which should match the Minecraft version,
         * whereas [project] is the arbitrary name part of the folder.
         *
         * Each project will also have a separate build script assigned depending on the loader,
         * named `build.{loader}.gradle.kts`.
         */
        fun match(project: String, vararg loaders: String, version: String = project) {
            for (loader in loaders) version("$project-$loader", version)
        }

        // Each loader gets its own build script, named `build.{loader}.gradle.kts`.
        mapBuilds { _, node ->
            "build.${node.project.substringAfterLast('-')}.gradle.kts"
        }

        // One node per loader, kept at the newest supported Minecraft version.
        // Add more `match(...)` lines (and matching sections in stonecutter.properties.toml)
        // to cover older Minecraft versions - see AGENTS.md.
        match("26.2", "fabric")
        match("1.17.1", "fabric")
        // TRAVERSAL-BEGIN 1.19-fabric
        match("1.19", "fabric")
        // TRAVERSAL-END 1.19-fabric
        // TRAVERSAL-BEGIN 1.19.1-fabric
        match("1.19.1", "fabric")
        // TRAVERSAL-END 1.19.1-fabric
        // TRAVERSAL-BEGIN 1.19.3-fabric
        match("1.19.3", "fabric")
        // TRAVERSAL-END 1.19.3-fabric
        // TRAVERSAL-BEGIN 1.19.4-fabric
        match("1.19.4", "fabric")
        // TRAVERSAL-END 1.19.4-fabric
        // TRAVERSAL-BEGIN 1.20-fabric
        match("1.20", "fabric")
        // TRAVERSAL-END 1.20-fabric
        // TRAVERSAL-BEGIN 1.21-fabric
        match("1.21", "fabric")
        // TRAVERSAL-END 1.21-fabric
        // TRAVERSAL-BEGIN 1.21.2-fabric
        match("1.21.2", "fabric")
        // TRAVERSAL-END 1.21.2-fabric
        // TRAVERSAL-BEGIN 1.21.9-fabric
        match("1.21.9", "fabric")
        // TRAVERSAL-END 1.21.9-fabric
        // TRAVERSAL-BEGIN 1.21.11-fabric
        match("1.21.11", "fabric")
        // TRAVERSAL-END 1.21.11-fabric
        // TRAVERSAL-BEGIN 26.1-fabric
        match("26.1", "fabric")
        // TRAVERSAL-END 26.1-fabric
        // TRAVERSAL-BEGIN 26.3-fabric
        match("26.3", "fabric")
        // TRAVERSAL-END 26.3-fabric

        match("26.1", "forge")
        match("26.1", "neoforge")

        // TRAVERSAL-BEGIN 1.17.1-forge
        match("1.17.1", "forge")
        // TRAVERSAL-END 1.17.1-forge
        // TRAVERSAL-BEGIN 1.18-forge
        match("1.18", "forge")
        // TRAVERSAL-END 1.18-forge




        // TRAVERSAL-BEGIN 1.19-forge
        match("1.19", "forge")
        // TRAVERSAL-END 1.19-forge



        // TRAVERSAL-BEGIN 1.19.3-forge
        match("1.19.3", "forge")
        // TRAVERSAL-END 1.19.3-forge

        // TRAVERSAL-BEGIN 1.19.4-forge
        match("1.19.4", "forge")
        // TRAVERSAL-END 1.19.4-forge

        // TRAVERSAL-BEGIN 1.20-forge
        match("1.20", "forge")
        // TRAVERSAL-END 1.20-forge
        // TRAVERSAL-BEGIN 1.20.6-forge
        match("1.20.6", "forge")
        // TRAVERSAL-END 1.20.6-forge






        // TRAVERSAL-BEGIN 1.21-forge
        match("1.21", "forge")
        // TRAVERSAL-END 1.21-forge


        // TRAVERSAL-BEGIN 1.21.3-forge
        match("1.21.3", "forge")
        // TRAVERSAL-END 1.21.3-forge



        // TRAVERSAL-BEGIN 1.21.6-forge
        match("1.21.6", "forge")
        // TRAVERSAL-END 1.21.6-forge



        // TRAVERSAL-BEGIN 1.21.9-forge
        match("1.21.9", "forge")
        // TRAVERSAL-END 1.21.9-forge


        // TRAVERSAL-BEGIN 1.21.11-forge
        match("1.21.11", "forge")
        // TRAVERSAL-END 1.21.11-forge



        // TRAVERSAL-BEGIN 26.2-forge
        match("26.2", "forge")
        // TRAVERSAL-END 26.2-forge

        // TRAVERSAL-BEGIN 26.3-forge
        match("26.3", "forge")
        // TRAVERSAL-END 26.3-forge

        // TRAVERSAL-BEGIN 1.20.4-neoforge
        match("1.20.4", "neoforge")
        // TRAVERSAL-END 1.20.4-neoforge

        // TRAVERSAL-BEGIN 1.20.6-neoforge
        match("1.20.6", "neoforge")
        // TRAVERSAL-END 1.20.6-neoforge

        // TRAVERSAL-BEGIN 1.21-neoforge
        match("1.21", "neoforge")
        // TRAVERSAL-END 1.21-neoforge


        // TRAVERSAL-BEGIN 1.21.2-neoforge
        match("1.21.2", "neoforge")
        // TRAVERSAL-END 1.21.2-neoforge







        // TRAVERSAL-BEGIN 1.21.9-neoforge
        match("1.21.9", "neoforge")
        // TRAVERSAL-END 1.21.9-neoforge


        // TRAVERSAL-BEGIN 1.21.11-neoforge
        match("1.21.11", "neoforge")
        // TRAVERSAL-END 1.21.11-neoforge



        // TRAVERSAL-BEGIN 26.2-neoforge
        match("26.2", "neoforge")
        // TRAVERSAL-END 26.2-neoforge

        // TRAVERSAL-BEGIN 26.3-neoforge
        match("26.3", "neoforge")
        // TRAVERSAL-END 26.3-neoforge

        vcsVersion = "26.2-fabric"
    }
}

rootProject.name = "streamchatbridge"
