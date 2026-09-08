package land.otter.roverd

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var nameView: EditText
    private lateinit var serverView: EditText
    private lateinit var baudView: EditText
    private lateinit var speedView: EditText
    private lateinit var brcLineView: Spinner
    private lateinit var brcActiveLowView: CheckBox

    private val statusListener: (String) -> Unit = { value ->
        runOnUiThread { statusView.text = "Status: $value" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        loadSettings()
        requestNotificationPermission()
        startRoverService()
    }

    override fun onStart() {
        super.onStart()
        RoverRuntimeState.addListener(statusListener)
    }

    override fun onStop() {
        RoverRuntimeState.removeListener(statusListener)
        super.onStop()
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Roverd Android"
            textSize = 26f
        })

        statusView = TextView(this).apply {
            text = "Status: Starting"
            textSize = 16f
            setPadding(0, pad / 2, 0, pad)
        }
        root.addView(statusView)

        nameView = edit(root, "Rover name")
        serverView = edit(root, "Server WebSocket URL")
        baudView = edit(root, "Serial baud")
        speedView = edit(root, "Max wheel speed (mm/s)")

        root.addView(TextView(this).apply { text = "BRC control line" })
        brcLineView = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                BrcLine.entries.map { it.name },
            )
        }
        root.addView(brcLineView)

        brcActiveLowView = CheckBox(this).apply {
            text = "BRC active state is LOW / control-line false"
        }
        root.addView(brcActiveLowView)

        root.addView(Button(this).apply {
            text = "Save and restart rover"
            setOnClickListener {
                saveSettings()
                startService(Intent(this@MainActivity, RoverService::class.java).setAction("restart"))
            }
        })

        root.addView(TextView(this).apply {
            text = "Initial build: USB serial, RTS/DTR BRC, Roomba sensor stream, drive/motor/raw commands, and server reconnect. Camera/audio come next."
            setPadding(0, pad, 0, 0)
        })

        return root
    }

    private fun edit(root: LinearLayout, label: String): EditText {
        root.addView(TextView(this).apply { text = label })
        return EditText(this).also { root.addView(it) }
    }

    private fun loadSettings() {
        val cfg = RoverSettings.load(this)
        nameView.setText(cfg.name)
        serverView.setText(cfg.serverUrl)
        baudView.setText(cfg.baud.toString())
        speedView.setText(cfg.maxWheelSpeed.toString())
        brcLineView.setSelection(BrcLine.entries.indexOf(cfg.brcLine).coerceAtLeast(0))
        brcActiveLowView.isChecked = cfg.brcActiveLow
    }

    private fun saveSettings() {
        val old = RoverSettings.load(this)
        val cfg = old.copy(
            name = nameView.text.toString().trim().ifEmpty { "android-rover" },
            serverUrl = serverView.text.toString().trim().ifEmpty { old.serverUrl },
            baud = baudView.text.toString().toIntOrNull() ?: old.baud,
            maxWheelSpeed = speedView.text.toString().toIntOrNull() ?: old.maxWheelSpeed,
            brcLine = BrcLine.entries.getOrElse(brcLineView.selectedItemPosition) { BrcLine.RTS },
            brcActiveLow = brcActiveLowView.isChecked,
        )
        RoverSettings.save(this, cfg)
    }

    private fun startRoverService() {
        startForegroundService(Intent(this, RoverService::class.java))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
