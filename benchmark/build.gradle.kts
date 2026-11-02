plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

val androidMinSdkVersion: String by project
val androidTargetSdkVersion: String by project

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.santimattius.android.benchmark"
    compileSdk = androidTargetSdkVersion.toInt()

    defaultConfig {
        minSdk = androidMinSdkVersion.toInt()
        targetSdk = androidTargetSdkVersion.toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The baselineprofile plugin maps this module's `benchmarkRelease` variant to the
    // release-like (minified, non-debuggable) `benchmarkRelease` build type it adds to :app.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
}

baselineProfile {
    // Generate on the connected device/emulator (no Gradle Managed Device).
    useConnectedDevices = true
}
