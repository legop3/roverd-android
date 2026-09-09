plugins {
    id("com.android.application") version "9.4.0" apply false
}

allprojects {
    configurations.configureEach {
        resolutionStrategy {
            // RootEncoder 2.8.x requires compileSdk 37. Keep the camera library on the
            // newest release built for Android 36 so the rover can retain compileSdk 36.
            force(
                "com.github.pedroSG94.RootEncoder:library:2.7.2",
                "com.github.pedroSG94.RootEncoder:common:2.7.2",
                "com.github.pedroSG94.RootEncoder:encoder:2.7.2",
                "com.github.pedroSG94.RootEncoder:rtmp:2.7.2",
                "com.github.pedroSG94.RootEncoder:rtsp:2.7.2",
                "com.github.pedroSG94.RootEncoder:srt:2.7.2",
                "com.github.pedroSG94.RootEncoder:udp:2.7.2",
                "com.github.pedroSG94.RootEncoder:whip:2.7.2",
            )
        }
    }
}
