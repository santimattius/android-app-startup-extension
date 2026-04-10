plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.mavenPublish)
}

val androidMinSdkVersion: String by project
val androidTargetSdkVersion: String by project

val libraryGroupId: String by project
val libraryArtifactId: String by project
val libraryVersion: String by project

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.santimattius.android.core"
    compileSdk = androidTargetSdkVersion.toInt()

    defaultConfig {
        minSdk = androidMinSdkVersion.toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(libraryGroupId, libraryArtifactId, libraryVersion)

    pom {
        name = "Android App Startup Extension"
        description =
            "App Startup Extension is a library based on AndroidX App Startup that optimizes component initialization in Android applications using Kotlin Coroutines."
        inceptionYear = "2025"
        url = "https://github.com/santimattius/android-app-startup-extension/"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "santiago-mattiauda"
                name = "Santiago Mattiauda"
                url = "https://github.com/santimattius"
            }
        }
        scm {
            url = "https://github.com/santimattius/android-app-startup-extension/"
            connection = "scm:git:git://github.com/santimattius/android-app-startup-extension.git"
            developerConnection = "scm:git:ssh://git@github.com/santimattius/android-app-startup-extension.git"
        }
    }
}