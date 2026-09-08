plugins {
    id("com.android.application")
}

val ciBuildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val roverSigningFile = rootProject.file("signing/roverd-update.p12")

android {
    namespace = "land.otter.roverd"
    compileSdk = 36

    defaultConfig {
        applicationId = "land.otter.roverd"
        minSdk = 17
        targetSdk = 36
        // Keep every CI build newer than the original versionCode=1 APK.
        versionCode = 1000 + ciBuildNumber
        versionName = "0.1.$ciBuildNumber"
    }

    signingConfigs {
        if (roverSigningFile.exists()) {
            create("roverUpdate") {
                storeFile = roverSigningFile
                storePassword = "roverd-update-key"
                keyAlias = "roverd"
                keyPassword = "roverd-update-key"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // CI decodes the permanent rover signing key before Gradle runs.
            // Local builds without that file continue to use Android's normal debug key.
            if (roverSigningFile.exists()) {
                signingConfig = signingConfigs.getByName("roverUpdate")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    // 3.12.x is the final OkHttp line supporting pre-Android-5 devices.
    // Rover connections are normally plain ws:// on the local network.
    implementation("com.squareup.okhttp3:okhttp:3.12.13")
}
