package land.otter.roverd

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashLogger {
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return

            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is Handler) {
                installed = true
                return
            }

            Thread.setDefaultUncaughtExceptionHandler(
                Handler(appContext, previous),
            )
            installed = true
            RoverRuntimeState.log("CRASH logger installed at process startup")
        }
    }

    private class Handler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            val trace = StringWriter().also { writer ->
                PrintWriter(writer).use { throwable.printStackTrace(it) }
            }.toString()
            val message = "FATAL thread=${thread.name}: $trace"

            // Normal path: goes to the rolling in-app log and persistent roverd.log.
            runCatching {
                RoverRuntimeState.initialize(context)
                RoverRuntimeState.log(message)
            }

            // Independent fallback so a failure inside RoverRuntimeState itself cannot hide a crash.
            runCatching {
                File(context.filesDir, "last-crash.txt").writeText(message)
            }

            previous?.uncaughtException(thread, throwable)
        }
    }
}
