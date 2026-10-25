plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.multiplatform) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
}
allprojects {
    dependencyLocking { lockAllConfigurations() }
}
// Invoked only by the CI dependency refresh; reviewed locks/hashes are committed.
tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("Dependency refresh inspects all project configurations")
    doLast {
        allprojects.forEach { project ->
            project.configurations.filter { it.isCanBeResolved }.forEach { configuration ->
                // Android app/test project outputs expose multiple secondary variants.
                // Dependency refresh only downloads external modules, like the shell helper.
                configuration.incoming.artifactView {
                    componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                }.files.files
            }
        }
    }
}
