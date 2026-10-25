pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx.*"); includeGroupByRegex("com\\.google.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 1.78.0's official AARs are released before Maven Central publication.
        // Restrict this artifact-only repository to the three pinned Filament modules.
        exclusiveContent {
            forRepository {
                ivy {
                    name = "FilamentRelease"
                    url = uri("https://github.com/google/filament/releases/download")
                    patternLayout { artifact("v[revision]/[artifact]-v[revision]-android.[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter {
                includeModule("com.google.android.filament", "filament-android")
                includeModule("com.google.android.filament", "gltfio-android")
                includeModule("com.google.android.filament", "filament-utils-android")
            }
        }
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx.*"); includeGroupByRegex("com\\.google.*") } }
        mavenCentral()
    }
}
rootProject.name = "AssistantMobile"
include(":core", ":plugin-api", ":plugin-trading")
project(":plugin-trading").projectDir = file("../plugins/trading/mobile")
// The Linux lane exercises the same common/JVM sources without an Android SDK.
if (!providers.gradleProperty("mobileJvmOnly").map(String::toBoolean).orElse(false).get()) {
    include(":design", ":androidPlatform", ":androidApp")
}
