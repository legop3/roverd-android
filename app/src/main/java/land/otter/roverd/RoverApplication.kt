package land.otter.roverd

import androidx.multidex.MultiDexApplication

class RoverApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        RoverRuntimeState.initialize(this)
        CrashLogger.install(this)
        RoverRuntimeState.log("APPLICATION onCreate complete")
    }
}
