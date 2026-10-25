plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "assistant.mobile.design"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true }
}
dependencies {
    api(libs.compose.material3)
    api(libs.compose.foundation)
    implementation(project(":core"))
}
