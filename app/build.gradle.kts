plugins {
    id("com.android.application")
}

android {
    namespace = "land.otter.roverd"
    compileSdk = 37

    defaultConfig {
        applicationId = "land.otter.roverd"
        minSdk = 26
        targetSdk = 37
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
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
}
