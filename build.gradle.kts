plugins {
    id("com.android.application") version "9.4.0" apply false
}

allprojects {
    configurations.configureEach {
        resolutionStrategy {
            // Keep every RootEncoder module on the same release. The camera subsystem uses the
            // current GenericStream API instead of the older RtspCamera2 compatibility wrapper.
            force(
                "com.github.pedroSG94.RootEncoder:library:2.8.1",
                "com.github.pedroSG94.RootEncoder:common:2.8.1",
                "com.github.pedroSG94.RootEncoder:encoder:2.8.1",
                "com.github.pedroSG94.RootEncoder:rtmp:2.8.1",
                "com.github.pedroSG94.RootEncoder:rtsp:2.8.1",
                "com.github.pedroSG94.RootEncoder:srt:2.8.1",
                "com.github.pedroSG94.RootEncoder:udp:2.8.1",
                "com.github.pedroSG94.RootEncoder:whip:2.8.1",
            )
        }
    }
}
