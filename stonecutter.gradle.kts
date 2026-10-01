plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.2-fabric" /* [SC] DO NOT EDIT */

// See https://stonecutter.kikugie.dev/wiki/config/params
stonecutter parameters {
    val (version, loader) = current.project.split('-', limit = 2)

    // Makes version- and loader-specific properties apply from `stonecutter.properties.toml`
    properties {
        tags(version, loader)
    }

    // Adds constants to Stonecutter comments (i.e. for `//? if fabric {...`)
    constants {
        match(loader, "fabric", "forge", "neoforge")
    }
}

// Per-version source overrides (versions/<node>/src/main/java) replace matching shared files.
// The ACTIVE node compiles the shared `src/` in place next to its own `src/`, so a shared file
// that is overridden locally would be compiled twice (duplicate class). Exclude those shared
// files; non-active nodes already get this via the generated sources (and won't match, since
// the shared `src/` isn't part of their source set).
subprojects {
    pluginManager.withPlugin("java") {
        afterEvaluate {
            val overrideRoot = layout.projectDirectory.dir("src/main/java")
            if (!overrideRoot.asFile.isDirectory) return@afterEvaluate

            val sharedRoot = rootProject.layout.projectDirectory.dir("src/main/java")
            val overridden = fileTree(overrideRoot).files.map { file ->
                val relative = overrideRoot.asFile.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                sharedRoot.asFile.toPath().resolve(relative).toString().replace(File.separatorChar, '/')
            }.toSet()

            val sourceSets = extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            sourceSets.named("main").get().getJava().exclude { entry: org.gradle.api.file.FileTreeElement ->
                entry.file.toPath().toString().replace(File.separatorChar, '/') in overridden
            }
        }
    }
}
