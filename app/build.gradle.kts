plugins {
    id("com.android.application")
}

android {
    namespace = "land.otter.roverd"
    compileSdk = 36

    defaultConfig {
        applicationId = "land.otter.roverd"
        minSdk = 17
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
