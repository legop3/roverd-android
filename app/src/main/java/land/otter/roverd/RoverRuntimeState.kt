package land.otter.roverd

import java.util.concurrent.CopyOnWriteArrayList

object RoverRuntimeState {
    @Volatile var status: String = "Stopped"
        private set

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun update(value: String) {
        status = value
        listeners.forEach { it(value) }
    }

    fun addListener(listener: (String) -> Unit) {
        listeners += listener
        listener(status)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners -= listener
    }
}
