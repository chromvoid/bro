import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import java.util.zip.ZipFile
import java.net.URI
import groovy.json.JsonSlurper
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.testing.Test
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

abstract class PrepareAmbientVad : Exec() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
}

abstract class PrepareAmbientVadFixture : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtureFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val directory = outputDirectory.get().asFile
        check(directory.isDirectory || directory.mkdirs())
        fixtureFile.get().asFile.copyTo(directory.resolve("ambient-vad-audio.json"), overwrite = true)
    }
}

abstract class PrepareBodyJniFixture : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtureFiles: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val directory = root.resolve("body")
        check(directory.isDirectory || directory.mkdirs())
        for (fixture in fixtureFiles.files) fixture.copyTo(directory.resolve(fixture.name), overwrite = true)
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}
val repositoryRoot = rootProject.projectDir.resolve("..").canonicalFile
val vadManifest = repositoryRoot.resolve("server/models/silero.json")
val vadModel = JsonSlurper().parse(vadManifest) as Map<*, *>
val vadModelFile = vadModel["file"] as String
val vadModelHash = vadModel["sha256"] as String
require(vadModelFile == "silero-v6.2.1.onnx" && vadModelHash.matches(Regex("[0-9a-f]{64}")))
val vadAssets = layout.buildDirectory.dir("generated/ambientVadAssets")
val prepareAmbientVad = tasks.register<PrepareAmbientVad>("prepareAmbientVad") {
    inputs.files(repositoryRoot.resolve("shell/tools/prepare_vad.py"), vadManifest,
        repositoryRoot.resolve("server/models/SILERO-LICENSE"),
        repositoryRoot.resolve("shell/licenses/ONNXRUNTIME-LICENSE.txt"),
        repositoryRoot.resolve("shell/licenses/ONNXRUNTIME-ThirdPartyNotices.txt"))
    outputDirectory.set(vadAssets)
    // The shared helper verifies cached bytes on every build; no model download exists in the app.
    outputs.upToDateWhen { false }
    commandLine("python3", repositoryRoot.resolve("shell/tools/prepare_vad.py").absolutePath,
        "--output-dir", vadAssets.get().asFile.absolutePath)
}
val prepareAmbientVadFixture = tasks.register<PrepareAmbientVadFixture>("prepareAmbientVadFixture") {
    fixtureFile.set(repositoryRoot.resolve("contracts/ambient-vad-audio.json"))
    outputDirectory.set(layout.buildDirectory.dir("generated/ambientVadTestAssets"))
}
val prepareBodyJniFixture = tasks.register<PrepareBodyJniFixture>("prepareBodyJniFixture") {
    fixtureFiles.from(repositoryRoot.resolve("web/src/assets/body/male.glb"),
        repositoryRoot.resolve("web/src/assets/body/male-lookaround.glb"),
        repositoryRoot.resolve("web/src/assets/body/LICENSE.CC0.md"),
        repositoryRoot.resolve("web/src/assets/body/LICENSE.MPFB.md"),
        repositoryRoot.resolve("tools/assets/ROKOKO_ANIMATION_NOTICE.md"))
    outputDirectory.set(layout.buildDirectory.dir("generated/bodyJniTestAssets"))
}

android {
    namespace = "assistant.mobile.android"
    compileSdk = 37
    val instrumentationBuildType = providers.gradleProperty("assistantTestBuildType").orElse("debug").get()
    require(instrumentationBuildType in setOf("debug", "release"))
    testBuildType = instrumentationBuildType
    if (instrumentationBuildType == "release") {
        // Release upgrade exercises the same minified APK with AGP's tested-app mapping.
        sourceSets.getByName("androidTest").java.setSrcDirs(emptyList<String>())
        sourceSets.getByName("androidTest").kotlin.setSrcDirs(listOf("src/upgradeTest/kotlin"))
    } else {
        sourceSets.getByName("androidTest").kotlin.srcDir("src/upgradeTest/kotlin")
    }
    defaultConfig {
        applicationId = "com.chromvoid.bro"
        minSdk = 26
        targetSdk = 36
        // Family phones and the Mac mini's 16 KiB emulator are both arm64.
        ndk { abiFilters += "arm64-v8a" }
        // The existing release tooling supplies minutes since 2026-01-01 by commit time.
        versionCode = providers.gradleProperty("assistantVersionCode").map(String::toInt).orElse(1).get()
            .also { require(it in 1..2_100_000_000) { "assistantVersionCode must be 1..2100000000" } }
        versionName = providers.gradleProperty("assistantVersionName").orElse("0.1.0").get()
        val certificatePin = rootProject.file("release-certificate.sha256")
            .readText().substringBefore(' ').trim()
        require(certificatePin.length == 64 && certificatePin.all { it in "0123456789abcdef" })
        buildConfigField("String", "RELEASE_CERTIFICATE_SHA256", "\"$certificatePin\"")
        buildConfigField("boolean", "PLAY_STORE", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "AMBIENT_VAD_MODEL_FILE", "\"$vadModelFile\"")
        buildConfigField("String", "AMBIENT_VAD_MODEL_SHA256", "\"$vadModelHash\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildTypes {
        val configuredOrigin = providers.gradleProperty("assistantOrigin")
            .orElse(providers.environmentVariable("ASSISTANT_ORIGIN"))
        for (name in listOf("debug", "release")) {
            getByName(name) {
                val origin = (if (name == "debug") providers.gradleProperty("assistantDevOrigin")
                    .orElse(configuredOrigin) else configuredOrigin)
                    .orElse(if (name == "debug") "http://127.0.0.1:8766" else "https://assistant.invalid").get()
                val url = URI(origin)
                require(url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null &&
                    url.rawPath.isNullOrEmpty() && url.host != null) {
                    "assistantOrigin must be an origin without credentials, path, query or fragment"
                }
                require(url.scheme == "https" || (name == "debug" && url.scheme == "http" &&
                    url.host in setOf("127.0.0.1", "localhost"))) {
                    "Only debug builds may use HTTP, restricted to loopback"
                }
                buildConfigField("String", "ASSISTANT_ORIGIN", "\"$origin\"")
                manifestPlaceholders["assistantHost"] = url.host
                manifestPlaceholders["assistantScheme"] = url.scheme
                manifestPlaceholders["assistantOrigin"] = origin
            }
        }
        getByName("debug") {
            // Core's unused legacy address-linkifier contains a framework browser reference.
            // Retain all other debug/test APIs; only that unreachable helper may be shrunk.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro", "proguard-debug.pro")
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            buildConfigField("boolean", "PLAY_STORE", "true")
        }
    }
    sourceSets.getByName("play").kotlin.srcDir("src/release/kotlin")
    for (name in listOf("debug", "release")) {
        sourceSets.getByName(name).kotlin.srcDir("src/direct/kotlin")
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        jniLibs {
            useLegacyPackaging = false
            // No native code calls Filament Utils; the renderer uses Filament
            // and gltfio directly. Keep their JNI libraries in the APK.
            excludes += "**/libfilament-utils-jni.so"
        }
    }
    // Keep the complete lint findings in CI logs, including dependency refresh runs.
    lint { textReport = true }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":plugin-api"))
    add("debugImplementation", project(":plugin-trading"))
    add("releaseImplementation", project(":plugin-trading"))
    implementation(project(":design"))
    implementation(project(":androidPlatform"))
    implementation(libs.activity.compose)
    implementation(libs.browser)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.serialization)
    implementation(libs.work)
    implementation(libs.onnxruntime.android)
    // Keep the Maven module identities for locking; the upstream release names
    // omit the module's -android suffix. All three public API dependencies are explicit.
    implementation(libs.filament.android) { artifact { name = "filament"; type = "aar"; extension = "aar" } }
    implementation(libs.filament.gltfio) { artifact { name = "gltfio"; type = "aar"; extension = "aar" } }
    implementation(libs.filament.utils) { artifact { name = "filament-utils"; type = "aar"; extension = "aar" } }
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    // The same pinned graph and host JNI verify the real VAD before the emulator smoke.
    testImplementation(libs.onnxruntime.desktop)
    androidTestCompileOnly(libs.errorprone.annotations)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.webrtc)
    debugImplementation(libs.compose.ui.test.manifest)
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareAmbientVad)
    systemProperty("assistant.whoop.frames", rootProject.file("../contracts/whoop-frames.json").absolutePath)
    systemProperty("assistant.vad.model", vadAssets.get().file(vadModelFile).asFile.absolutePath)
    systemProperty("assistant.vad.fixture", repositoryRoot.resolve("contracts/ambient-vad.json").absolutePath)
    systemProperty("assistant.vad.audio", repositoryRoot.resolve("contracts/ambient-vad-audio.json").absolutePath)
    systemProperty("assistant.ring.manual.fixture", rootProject.file("../contracts/ring-manual-observations.json").absolutePath)
}


// Keep material source reviewable. The CI bootstrap verifies the upstream archive
// and supplies matc from the very same release as the Android runtime libraries.
abstract class CompileBodyMaterials : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val compiler: RegularFileProperty

    @get:Input
    abstract val compilerVersion: Property<String>

    @get:Input
    abstract val runtimeVersion: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val noticeFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun compile() {
        check(compilerVersion.get() == runtimeVersion.get()) {
            "BodyAtlas matc and Filament runtime versions must match"
        }
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val output = root.resolve("body/materials")
        output.mkdirs()
        val sources = sourceDirectory.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "mat" }.sortedBy { it.name }.toList()
        check(sources.isNotEmpty()) { "BodyAtlas material sources are missing" }
        check(sources.map { it.name }.distinct().size == sources.size) { "Duplicate BodyAtlas material names" }
        for (source in sources) {
            execOperations.exec {
                commandLine(compiler.get().asFile.absolutePath, "-p", "mobile", "-a", "opengl", "-S",
                    "-o", output.resolve(source.nameWithoutExtension + ".filamat").absolutePath, source.absolutePath)
            }
        }
        val notices = root.resolve("body/licenses")
        notices.mkdirs()
        for (notice in noticeFiles.files) notice.copyTo(notices.resolve(notice.name), overwrite = true)
    }
}

val compileBodyMaterials = tasks.register<CompileBodyMaterials>("compileBodyMaterials") {
    sourceDirectory.set(layout.projectDirectory.dir("src/main/materials"))
    compiler.set(layout.file(providers.environmentVariable("FILAMENT_MATC").map { file(it) }))
    compilerVersion.set(providers.environmentVariable("FILAMENT_VERSION"))
    runtimeVersion.set(libs.versions.filament)
    noticeFiles.from(rootProject.fileTree("../web/src/assets/body") { include("LICENSE.*.md") },
        rootProject.file("../tools/assets/ROKOKO_ANIMATION_NOTICE.md"))
    outputDirectory.set(layout.buildDirectory.dir("generated/bodyMaterials/assets"))
}
// Read the main classes selected by AGP for this Android variant, including
// project libraries. Third-party implementation details are not our UI contract.
abstract class VerifyOwnAndroidClasses : DefaultTask() {
    @get:Input
    abstract val privatePlugins: Property<Boolean>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allDirectories: ListProperty<Directory>

    @TaskAction
    fun verify() {
        val requiredPackages = setOf("android", "core", "design", "platform", "pluginapi") +
            if (privatePlugins.get()) setOf("trading") else emptySet()
        val seenPackages = mutableSetOf<String>()
        var checkedClasses = 0
        fun isOwnClass(name: String) = name.startsWith("assistant/mobile/") && name.endsWith(".class")
        fun inspect(bytes: ByteArray, name: String, container: String) {
            check(privatePlugins.get() || !name.startsWith("assistant/mobile/trading/")) {
                "Private plugin class in the Play variant: $container!/$name"
            }
            val constants = bytes.toString(Charsets.ISO_8859_1)
            check(!constants.contains("android/webkit/") && !constants.contains("android.webkit.")) {
                "Native main class references android.webkit: $container!/$name"
            }
            seenPackages += name.removePrefix("assistant/mobile/").substringBefore('/')
            checkedClasses++
        }
        for (jar in allJars.get()) {
            ZipFile(jar.asFile).use { archive ->
                for (entry in archive.entries().asSequence().filter { isOwnClass(it.name) }.sortedBy { it.name }) {
                    archive.getInputStream(entry).use { inspect(it.readBytes(), entry.name, jar.asFile.path) }
                }
            }
        }
        for (directory in allDirectories.get()) {
            val root = directory.asFile
            for (file in root.walkTopDown().filter { it.isFile && it.extension == "class" }.sortedBy { it.path }) {
                val name = file.relativeTo(root).invariantSeparatorsPath
                if (isOwnClass(name)) inspect(file.readBytes(), name, root.path)
            }
        }
        check(seenPackages.containsAll(requiredPackages)) {
            "Native main bytecode coverage missing ${requiredPackages - seenPackages}; checked $checkedClasses classes"
        }
        logger.lifecycle("Verified $checkedClasses assistant.mobile main classes: ${seenPackages.sorted()}")
    }
}

val verifyNativeRuntime = tasks.register("verifyNativeRuntime") {
    notCompatibleWithConfigurationCache("Runtime verification inspects the resolved dependency graph")
    doLast {
        for (name in listOf("debugRuntimeClasspath", "releaseRuntimeClasspath", "playRuntimeClasspath")) {
            val runtime = configurations.getByName(name)
            val forbidden = runtime.incoming.resolutionResult.allComponents.filter {
                (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group == "androidx.webkit"
            }
            check(forbidden.isEmpty()) { "WebKit in $name: $forbidden" }
        }
    }
}

androidComponents.onVariants { variant ->
    val currentUrl = URI(android.buildTypes.getByName(requireNotNull(variant.buildType))
        .manifestPlaceholders.getValue("assistantOrigin") as String)
    val isDebug = variant.buildType == "debug"
    // Resolve only the requested variant: a debug loopback previous must not validate as release.
    val previous = providers.gradleProperty("assistantPreviousOrigin")
        .orElse(providers.environmentVariable("ASSISTANT_PREVIOUS_ORIGIN")).orElse("").map { value ->
            val url = value.takeIf(String::isNotEmpty)?.let(::URI)
            if (url != null) {
                require(url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null &&
                    url.rawPath.isNullOrEmpty() && url.host != null) {
                    "assistantPreviousOrigin must be an origin without credentials, path, query or fragment"
                }
                require(url.scheme == "https" || (isDebug && url.scheme == "http" &&
                    url.host in setOf("127.0.0.1", "localhost"))) {
                    "Only debug builds may use HTTP, restricted to loopback"
                }
                fun port(origin: URI) = if (origin.port != -1) origin.port else if (origin.scheme == "https") 443 else 80
                require(url.scheme != currentUrl.scheme || !url.host.equals(currentUrl.host, ignoreCase = true) ||
                    port(url) != port(currentUrl)) {
                    "assistantPreviousOrigin must differ from assistantOrigin"
                }
            }
            value to (url?.host ?: currentUrl.host)
        }
    variant.manifestPlaceholders.put("assistantPreviousHost", previous.map { it.second })
    variant.manifestPlaceholders.put("assistantPreviousOrigin", previous.map { it.first })
    variant.sources.assets?.addGeneratedSourceDirectory(prepareAmbientVad) { it.outputDirectory }
    variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(prepareAmbientVadFixture) { it.outputDirectory }
    variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(prepareBodyJniFixture) { it.outputDirectory }
    variant.sources.assets?.addGeneratedSourceDirectory(compileBodyMaterials, CompileBodyMaterials::outputDirectory)
    val verifyClasses = tasks.register<VerifyOwnAndroidClasses>(
        "verify${variant.name.replaceFirstChar(Char::uppercaseChar)}OwnAndroidClasses",
    ) { privatePlugins.set(variant.buildType != "play") }
    // The public variant API supplies compilation dependencies and the concrete
    // Android variants of core/design/platform/plugin-api, with no second resolution.
    variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(verifyClasses).toGet(
        ScopedArtifact.CLASSES,
        VerifyOwnAndroidClasses::allJars,
        VerifyOwnAndroidClasses::allDirectories,
    )
    verifyNativeRuntime.configure { dependsOn(verifyClasses) }
}
