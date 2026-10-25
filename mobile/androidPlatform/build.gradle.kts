import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory

plugins {
    alias(libs.plugins.android.library)
}
abstract class BuildRingRust : Exec() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
}

android {
    namespace = "assistant.mobile.platform"
    compileSdk = 37
    ndkVersion = "29.0.13846066"
    defaultConfig {
        minSdk = 26
        buildConfigField("boolean", "PLAY_STORE", "false")
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            buildConfigField("boolean", "PLAY_STORE", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
val buildRingRust = tasks.register<BuildRingRust>("buildRingRust") {
    val ringRoot = rootProject.layout.projectDirectory.dir("../shell/ring")
    inputs.files(fileTree(ringRoot) { exclude("target/**") })
    // The native APK and its CI emulator only consume arm64. The shell keeps
    // the shared helper's default pair of Android targets.
    inputs.property("rustTarget", "aarch64-linux-android")
    outputDirectory.set(layout.buildDirectory.dir("generated/ringJni"))
    commandLine("bash", ringRoot.file("build-android.sh").asFile.absolutePath,
        "--ndk", "${System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/Library/Android/sdk"}/ndk/29.0.13846066",
        "--output", outputDirectory.get().asFile.absolutePath,
        "--target", "aarch64-linux-android")
}
androidComponents.onVariants { variant ->
    if (variant.buildType != "play") {
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRingRust) { it.outputDirectory }
    }
}
dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.client.okhttp)
    implementation(libs.webrtc)
    implementation(libs.work)
    implementation(libs.play.services.location)
    implementation(libs.serialization)
}
