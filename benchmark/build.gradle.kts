plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
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

    // The target is the release-like, profileable `benchmark` build of :app.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
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

androidComponents {
    // Only the `benchmark` variant is meaningful; skip debug/release test variants.
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}
