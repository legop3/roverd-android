package land.otter.roverd

import android.app.Application

class RoverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RoverRuntimeState.initialize(this)
        CrashLogger.install(this)
        RoverRuntimeState.log("APPLICATION onCreate complete")
    }
}
