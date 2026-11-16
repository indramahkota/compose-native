package dev.brahmkshatriya.compose

import java.io.File
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

internal fun Project.configureDesktopNativeExecutable(executableSpec: DesktopNativeExecutable) {
    executableSpec.requiredEntryPoint()
    val targets = kotlinNativeTargets()
    val hostTarget =
        hostDesktopNativeTarget(System.getProperty("os.name"), System.getProperty("os.arch"))
    DESKTOP_NATIVE_TARGET_SOURCE_SETS.forEach { (nativeTarget, conventionalTargetName) ->
        val isHostTarget = nativeTarget == hostTarget?.nativeTarget
        val target = targets.findByName(conventionalTargetName) ?: return@forEach
        target.configureNativeExecutable(
            executableSpec = executableSpec,
            conventionalTargetName = conventionalTargetName,
            buildTypes =
                if (isHostTarget) {
                    setOf(NativeBuildType.DEBUG, NativeBuildType.RELEASE)
                } else {
                    setOf(NativeBuildType.RELEASE)
                },
        )
    }
    if (hostTarget != null) configureHostDesktopRunAliases(hostTarget)
}

internal fun Project.createDesktopNativeTargets(requestedTargets: Set<String> = emptySet()) {
    pluginManager.withPlugin(KOTLIN_MULTIPLATFORM_PLUGIN_ID) {
        val targetsToCreate =
            if (requestedTargets.isEmpty()) {
                DESKTOP_NATIVE_TARGET_SOURCE_SETS.values
            } else {
                DESKTOP_NATIVE_TARGET_SOURCE_SETS.values.filter { it in requestedTargets }
            }
        targetsToCreate.forEach { targetName ->
            createKotlinNativeTarget(targetName)
        }
    }
}

private fun Project.configureHostDesktopRunAliases(hostTarget: HostDesktopNativeTarget) {
    afterEvaluate {
        listOf("Debug", "Release").forEach { buildType ->
            val sourceTaskName = "run${buildType}Executable${hostTarget.concreteTaskSuffix}"
            val sourceTask = tasks.findByName(sourceTaskName) as? Exec ?: return@forEach
            val aliasTaskName = "run${buildType}Executable$HOST_DESKTOP_TARGET_NAME_CAPITALIZED"
            if (tasks.findByName(aliasTaskName) == null) {
                tasks.register(aliasTaskName, Exec::class.java) { alias ->
                    alias.group = sourceTask.group
                    alias.description =
                        "Executes Kotlin/Native ${buildType.lowercase()} executable for the current desktop host."
                    alias.enabled = sourceTask.enabled
                    alias.commandLine(sourceTask.commandLine)
                    alias.workingDir = sourceTask.workingDir
                    alias.environment(sourceTask.environment)
                    alias.dependsOn(sourceTask.taskDependencies.getDependencies(sourceTask))
                }
            }
        }
    }
}

private fun Any.configureNativeExecutable(
    executableSpec: DesktopNativeExecutable,
    conventionalTargetName: String,
    buildTypes: Set<NativeBuildType>,
) {
    val binaries = nativeBinaries()
    val existingExecutables =
        (binaries as Iterable<*>).filterNotNull().filter { it.isNativeExecutable() }
    val configuredBuildTypes =
        existingExecutables.mapNotNull { binary ->
            val buildType = binary.nativeBuildType()
            if (buildType in buildTypes) {
                binary.configureNativeExecutable(executableSpec, conventionalTargetName)
                buildType
            } else {
                null
            }
        }
    val missingBuildTypes = buildTypes - configuredBuildTypes.toSet()
    if (missingBuildTypes.isEmpty()) return

    binaries.createNativeExecutables(
        missingBuildTypes,
        Action<Any> { binary ->
            binary.configureNativeExecutable(executableSpec, conventionalTargetName)
        },
    )
}

private fun Any.configureNativeExecutable(
    executableSpec: DesktopNativeExecutable,
    conventionalTargetName: String,
) {
    setNativeEntryPoint(executableSpec.requiredEntryPoint())
    val defaultSearchPaths =
        when (conventionalTargetName) {
            "linuxX64" ->
                listOf(
                    "/usr/lib/x86_64-linux-gnu",
                    "/lib/x86_64-linux-gnu",
                    "/usr/x86_64-linux-gnu/lib",
                    "/usr/local/lib",
                    "/usr/lib",
                )
            "linuxArm64" ->
                listOf(
                    "/usr/lib/aarch64-linux-gnu",
                    "/lib/aarch64-linux-gnu",
                    "/usr/aarch64-linux-gnu/lib",
                    "/usr/local/lib",
                    "/usr/lib",
                )
            "macosArm64" -> listOf("/opt/homebrew/lib", "/opt/homebrew/opt/sdl3/lib", "/usr/local/lib")
            "macosX64" -> listOf("/usr/local/lib", "/usr/local/opt/sdl3/lib")
            else -> emptyList()
        }
    val existingSearchPaths =
        defaultSearchPaths.filter { File(it).isDirectory }.map { "-L$it" }
    addNativeLinkerOptions(existingSearchPaths + executableSpec.linkerOptions)
}

private const val HOST_DESKTOP_TARGET_NAME_CAPITALIZED = "Desktop"
