import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

// Prevents ForgeGradle from frying your computer by recompiling Minecraft on multiple versions at once
interface ForgeMutex : BuildService<BuildServiceParameters.None>

val mutex = gradle.sharedServices.registerIfAbsent("forgeMinecraftPrepMutex", ForgeMutex::class.java) {
    maxParallelUsages.set(1)
}

tasks.matching { task ->
    task.name == "createMcp2Srg" ||
        task.name == "extractSrg" ||
        task.name == "applyPatches" ||
        task.name == "setupLayers" ||
        task.name == "setupLayersWorkspace"
}.configureEach {
    usesService(mutex)
}
