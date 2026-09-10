package land.otter.roverd

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.floor

class StatusActivity : Activity() {
    companion object {
        private const val FIRST_RUN_PERMISSION_REQUEST = 100
        private const val PREFS = "roverd_first_run"
        private const val KEY_PROVISIONING_ATTEMPTED = "provisioning_attempted"
        private const val REFRESH_MS = 500L
    }

    private enum class Health { GOOD, WARN, BAD, OFF }

    private data class StatusRowViews(
        val background: GradientDrawable,
        val dot: TextView,
        val value: TextView,
        val detail: TextView,
    )

    private lateinit var roverNameView: TextView
    private lateinit var overallView: TextView
    private lateinit var runtimeMessageView: TextView
    private lateinit var overallBackground: GradientDrawable

    private val rows = linkedMapOf<String, StatusRowViews>()
    private val handler = Handler(Looper.getMainLooper())
    private var firstRunPromptInFlight = false

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoverRuntimeState.initialize(this)
        setContentView(buildDashboard())

        ensureConfiguredServices()
        runFirstLaunchProvisioningIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
    }

    override fun onResume() {
        super.onResume()
        ensureConfiguredServices()
        refreshStatus()
    }

    override fun onStop() {
        handler.removeCallbacks(refreshTask)
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun buildDashboard(): View {
        rows.clear()
        val density = resources.displayMetrics.density
        fun dp(value: Float) = (value * density + 0.5f).toInt()

        val config = resources.configuration
        val widthDp = if (config.screenWidthDp > 0) config.screenWidthDp else (resources.displayMetrics.widthPixels / density).toInt()
        val heightDp = if (config.screenHeightDp > 0) config.screenHeightDp else (resources.displayMetrics.heightPixels / density).toInt()
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

        val outerPadDp = if (widthDp >= 700) 14f else 8f
        val gapDp = if (widthDp >= 700) 8f else 6f
        val availableWidthDp = widthDp - outerPadDp * 2f

        // Pick a column count from both width and aspect ratio. Cards stay near a useful physical
        // size instead of growing with the display height.
        val maxColumnsByWidth = floor((availableWidthDp + gapDp) / (148f + gapDp)).toInt().coerceIn(1, 6)
        val preferredColumns = when {
            widthDp >= 950 && landscape -> 5
            landscape -> 4
            widthDp >= 720 -> 4
            widthDp >= 500 -> 3
            else -> 2
        }
        val columns = minOf(maxColumnsByWidth, preferredColumns).coerceAtLeast(if (widthDp >= 300) 2 else 1)
        val cardWidthDp = ((availableWidthDp - gapDp * (columns - 1)) / columns).coerceAtLeast(138f)
        val textScale = (cardWidthDp / 180f).coerceIn(0.92f, 1.18f)
        val cardHeightDp = when {
            landscape && heightDp < 400 -> 82f
            landscape -> 88f
            else -> 94f
        } * textScale.coerceIn(0.94f, 1.10f)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0D1117"))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(outerPadDp), dp(if (landscape) 7f else 10f), dp(outerPadDp), dp(8f))
        }
        scroll.addView(root)

        val header = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (landscape) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
        }
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, if (landscape) dp(10f) else 0, if (landscape) 0 else dp(7f))
        }
        identity.addView(TextView(this).apply {
            text = "ROVERD"
            textSize = 12f * textScale
            setTextColor(Color.parseColor("#8B949E"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            if (Build.VERSION.SDK_INT >= 21) letterSpacing = 0.12f
        })
        roverNameView = TextView(this).apply {
            textSize = 26f * textScale
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        identity.addView(roverNameView)
        identity.addView(TextView(this).apply {
            text = "Dedicated rover runtime"
            textSize = 12f * textScale
            setTextColor(Color.parseColor("#8B949E"))
            maxLines = 1
        })
        header.addView(
            identity,
            if (landscape) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        overallBackground = roundedBackground("#30363D", dp(10f))
        val overallCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11f), dp(if (landscape) 7f else 9f), dp(11f), dp(if (landscape) 7f else 9f))
            background = overallBackground
        }
        overallView = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setAutoSize(this, 14, (20f * textScale).toInt().coerceAtLeast(17))
        }
        runtimeMessageView = TextView(this).apply {
            setTextColor(Color.parseColor("#F0F6FC"))
            maxLines = if (landscape) 1 else 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2f), 0, 0)
            setAutoSize(this, 10, (13f * textScale).toInt().coerceAtLeast(11))
        }
        overallCard.addView(overallView)
        overallCard.addView(runtimeMessageView)
        header.addView(
            overallCard,
            if (landscape) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.58f)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(7f)
        })

        val entries = listOf(
            Triple("server", "CORE", "ROVER SERVER"),
            Triple("usb", "CORE", "USB SERIAL"),
            Triple("sensors", "CORE", "ROOMBA SENSORS"),
            Triple("camera", "MEDIA", "CAMERA / VIDEO"),
            Triple("mic", "MEDIA", "MICROPHONE / AUDIO UP"),
            Triple("speaker", "MEDIA", "SPEAKER / AUDIO DOWN"),
            Triple("wake", "POWER", "CPU WAKE LOCK"),
            Triple("wifi", "POWER", "WI-FI HIGH PERFORMANCE"),
            Triple("doze", "POWER", "BATTERY OPTIMIZATION"),
            Triple("dock", "ROOMBA", "HOME BASE"),
            Triple("charging", "ROOMBA", "CHARGING"),
            Triple("autochg", "ROOMBA", "AUTO CHARGE"),
        )

        val grid = GridLayout(this).apply {
            columnCount = columns
            rowCount = (entries.size + columns - 1) / columns
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }

        entries.forEachIndexed { index, (key, section, label) ->
            val rowIndex = index / columns
            val columnIndex = index % columns
            val card = buildStatusCard(
                key = key,
                section = section,
                label = label,
                textScale = textScale,
                radius = dp(9f),
            )
            grid.addView(card, GridLayout.LayoutParams(GridLayout.spec(rowIndex), GridLayout.spec(columnIndex)).apply {
                width = dp(cardWidthDp)
                height = dp(cardHeightDp)
                if (columnIndex > 0) leftMargin = dp(gapDp)
                if (rowIndex > 0) topMargin = dp(gapDp)
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "Live status • 0.5 s refresh • Back opens configuration"
            textSize = 10f * textScale
            setTextColor(Color.parseColor("#6E7681"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6f), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        return scroll
    }

    private fun buildStatusCard(
        key: String,
        section: String,
        label: String,
        textScale: Float,
        radius: Int,
    ): View {
        val density = resources.displayMetrics.density
        fun dp(value: Float) = (value * density + 0.5f).toInt()

        val cardBackground = roundedBackground("#30363D", radius)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9f), dp(6f), dp(9f), dp(6f))
            background = cardBackground
        }

        val topLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = TextView(this).apply {
            text = "●"
            textSize = 23f * textScale
            gravity = Gravity.CENTER
        }
        topLine.addView(dot, LinearLayout.LayoutParams(dp(27f * textScale), LinearLayout.LayoutParams.WRAP_CONTENT))
        topLine.addView(TextView(this).apply {
            text = "$section · $label"
            setTextColor(Color.parseColor("#F0F6FC"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (Build.VERSION.SDK_INT >= 21) letterSpacing = 0.03f
            setAutoSize(this, 10, (13f * textScale).toInt().coerceAtLeast(11))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val valueView = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(1f), 0, 0)
            setAutoSize(this, 14, (20f * textScale).toInt().coerceAtLeast(17))
        }
        val detailView = TextView(this).apply {
            setTextColor(Color.parseColor("#F0F6FC"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(1f), 0, 0)
            setAutoSize(this, 10, (13f * textScale).toInt().coerceAtLeast(11))
        }

        card.addView(topLine)
        card.addView(valueView)
        card.addView(detailView)
        rows[key] = StatusRowViews(cardBackground, dot, valueView, detailView)
        return card
    }

    private fun setAutoSize(view: TextView, minSp: Int, maxSp: Int) {
        if (Build.VERSION.SDK_INT >= 26) {
            view.setAutoSizeTextTypeUniformWithConfiguration(
                minSp,
                maxSp.coerceAtLeast(minSp),
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
        } else {
            view.textSize = maxSp.coerceAtLeast(minSp).toFloat()
        }
    }

    private fun roundedBackground(color: String, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = radius.toFloat()
    }

    private fun refreshStatus() {
        if (!::roverNameView.isInitialized) return
        val cfg = RoverSettings.load(this)
        val now = System.currentTimeMillis()
        roverNameView.text = cfg.name
        runtimeMessageView.text = RoverRuntimeState.status

        val sensorAge = ageMs(now, RoverRuntimeState.lastSensorAtMs)
        val cameraAge = ageMs(now, RoverRuntimeState.lastCameraPublishedAtMs)
        val micAge = ageMs(now, MicRuntimeState.lastFrameAtMs)

        setRow(
            "server",
            if (RoverRuntimeState.serverConnected) Health.GOOD else Health.BAD,
            if (RoverRuntimeState.serverConnected) "Connected" else "Disconnected — retrying",
            cfg.serverUrl,
        )
        setRow(
            "usb",
            if (RoverRuntimeState.usbConnected) Health.GOOD else Health.BAD,
            if (RoverRuntimeState.usbConnected) "Connected" else "Disconnected — retrying",
            RoverRuntimeState.usbDevice.ifBlank { cfg.usbSerialPreference },
        )
        val sensorHealth = when {
            !RoverRuntimeState.usbConnected -> Health.BAD
            RoverRuntimeState.lastSensorAtMs == 0L -> Health.WARN
            sensorAge > 5_000L -> Health.BAD
            sensorAge > 2_000L -> Health.WARN
            else -> Health.GOOD
        }
        setRow(
            "sensors",
            sensorHealth,
            when (sensorHealth) {
                Health.GOOD -> "Streaming"
                Health.WARN -> "Waiting for fresh data"
                else -> "No fresh sensor data"
            },
            "${RoverRuntimeState.sensorFrames} frames • last ${formatAge(sensorAge, RoverRuntimeState.lastSensorAtMs)}",
        )

        if (!cfg.cameraEnabled) {
            setRow("camera", Health.OFF, "Disabled", "Not configured to publish video")
        } else {
            val cameraGood = RoverRuntimeState.cameraRunning && RoverRuntimeState.cameraPublisherConnected &&
                RoverRuntimeState.lastCameraPublishedAtMs != 0L && cameraAge <= 5_000L
            val cameraHealth = if (cameraGood) Health.GOOD else if (RoverRuntimeState.cameraRunning) Health.WARN else Health.BAD
            setRow(
                "camera",
                cameraHealth,
                if (cameraGood) "Publishing" else "${RoverRuntimeState.cameraState} — retrying",
                "${RoverRuntimeState.cameraWidth}×${RoverRuntimeState.cameraHeight} @ ${RoverRuntimeState.cameraFps} fps • last ${formatAge(cameraAge, RoverRuntimeState.lastCameraPublishedAtMs)}" +
                    errorSuffix(RoverRuntimeState.cameraLastError),
            )
        }

        if (!cfg.micEnabled) {
            setRow("mic", Health.OFF, "Disabled", "Not configured to publish microphone audio")
        } else {
            val micGood = MicRuntimeState.running && MicRuntimeState.connected &&
                MicRuntimeState.lastFrameAtMs != 0L && micAge <= 5_000L
            val micHealth = if (micGood) Health.GOOD else if (MicRuntimeState.running) Health.WARN else Health.BAD
            setRow(
                "mic",
                micHealth,
                if (micGood) "Publishing" else "${MicRuntimeState.state} — retrying",
                "${MicRuntimeState.measuredBitrate} bps • last ${formatAge(micAge, MicRuntimeState.lastFrameAtMs)}" + errorSuffix(MicRuntimeState.lastError),
            )
        }

        if (!cfg.audioPlaybackEnabled) {
            setRow("speaker", Health.OFF, "Disabled", "Reverse audio playback is not configured")
        } else {
            val speakerHealth = if (AudioPlaybackRuntimeState.playing) Health.GOOD else if (AudioPlaybackRuntimeState.running) Health.WARN else Health.BAD
            setRow(
                "speaker",
                speakerHealth,
                if (AudioPlaybackRuntimeState.playing) "Playing" else "${AudioPlaybackRuntimeState.state} — retrying",
                "volume ${AudioPlaybackRuntimeState.effectiveVolume} • reconnects ${AudioPlaybackRuntimeState.reconnects}" + errorSuffix(AudioPlaybackRuntimeState.lastError),
            )
        }

        setRow("wake", if (RoverRuntimeState.wakeLockHeld) Health.GOOD else Health.BAD, if (RoverRuntimeState.wakeLockHeld) "Held" else "Not held", "Keeps rover runtime executing")
        setRow("wifi", if (RoverRuntimeState.wifiLockHeld) Health.GOOD else Health.BAD, if (RoverRuntimeState.wifiLockHeld) "Held" else "Not held", "Keeps Wi-Fi in high-performance mode")

        val dozeExempt = if (Build.VERSION.SDK_INT >= 23) {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        } else true
        setRow(
            "doze",
            if (dozeExempt) Health.GOOD else Health.WARN,
            if (dozeExempt) "Exempt" else "Optimization active",
            if (Build.VERSION.SDK_INT >= 23) "Android Doze / battery optimization" else "Not applicable on this Android version",
        )

        setRow(
            "dock",
            if (RoverRuntimeState.homeBaseDetected) Health.GOOD else Health.OFF,
            if (RoverRuntimeState.homeBaseDetected) "Detected" else "Not detected",
            "charge sources ${if (RoverRuntimeState.chargeSources < 0) "unknown" else "0x%02X".format(RoverRuntimeState.chargeSources)}",
        )
        setRow(
            "charging",
            if (RoverRuntimeState.roombaCharging) Health.GOOD else Health.OFF,
            if (RoverRuntimeState.roombaCharging) "Charging" else "Not charging",
            "charging state ${if (RoverRuntimeState.chargingState < 0) "unknown" else RoverRuntimeState.chargingState}",
        )
        setRow(
            "autochg",
            if (RoverRuntimeState.autoChargeTimerActive) Health.WARN else Health.GOOD,
            RoverRuntimeState.autoChargeState,
            "seek count ${RoverRuntimeState.autoChargeSeekCount}",
        )

        val requiredHealth = mutableListOf(
            RoverRuntimeState.serverConnected,
            RoverRuntimeState.usbConnected,
            RoverRuntimeState.wakeLockHeld,
            RoverRuntimeState.wifiLockHeld,
        )
        if (cfg.cameraEnabled) requiredHealth += RoverRuntimeState.cameraRunning && RoverRuntimeState.cameraPublisherConnected
        if (cfg.micEnabled) requiredHealth += MicRuntimeState.running && MicRuntimeState.connected
        if (cfg.audioPlaybackEnabled) requiredHealth += AudioPlaybackRuntimeState.running && AudioPlaybackRuntimeState.playing

        val healthy = requiredHealth.all { it }
        val overallHealth = if (healthy) Health.GOOD else Health.WARN
        overallView.text = if (healthy) "●  ALL SYSTEMS ONLINE" else "●  RECOVERING / DEGRADED"
        overallView.setTextColor(Color.WHITE)
        overallBackground.setColor(cardBackgroundColor(overallHealth))
    }

    private fun setRow(key: String, health: Health, value: String, detail: String) {
        val row = rows[key] ?: return
        row.background.setColor(cardBackgroundColor(health))
        row.dot.setTextColor(healthAccentColor(health))
        row.value.text = value
        row.detail.text = detail
    }

    private fun cardBackgroundColor(health: Health): Int = Color.parseColor(
        when (health) {
            Health.GOOD -> "#1F6F3D"
            Health.WARN -> "#8A5A00"
            Health.BAD -> "#9B2C2C"
            Health.OFF -> "#30363D"
        },
    )

    private fun healthAccentColor(health: Health): Int = Color.parseColor(
        when (health) {
            Health.GOOD -> "#7EE787"
            Health.WARN -> "#E3B341"
            Health.BAD -> "#FF7B72"
            Health.OFF -> "#8B949E"
        },
    )

    private fun ageMs(now: Long, timestamp: Long): Long = if (timestamp <= 0L) Long.MAX_VALUE else (now - timestamp).coerceAtLeast(0L)

    private fun formatAge(ageMs: Long, timestamp: Long): String = when {
        timestamp <= 0L -> "never"
        ageMs < 1_000L -> "${ageMs}ms ago"
        ageMs < 60_000L -> "${ageMs / 1_000}s ago"
        else -> "${ageMs / 60_000}m ago"
    }

    private fun errorSuffix(error: String): String = if (error.isBlank()) "" else " • ${error.take(120)}"

    private fun ensureConfiguredServices() {
        val cfg = RoverSettings.load(this)
        startServiceCompat(Intent(this, RoverService::class.java))

        if (cfg.audioPlaybackEnabled) {
            startServiceCompat(Intent(this, AudioPlaybackService::class.java).setAction(AudioPlaybackService.ACTION_START))
        }
        if (cfg.cameraEnabled && Build.VERSION.SDK_INT >= 21 && hasRuntimePermission(Manifest.permission.CAMERA)) {
            startServiceCompat(Intent(this, CameraPublisherService::class.java).setAction(CameraPublisherService.ACTION_START))
        }
        if (cfg.micEnabled && hasRuntimePermission(Manifest.permission.RECORD_AUDIO)) {
            startServiceCompat(Intent(this, MicPublisherService::class.java).setAction(MicPublisherService.ACTION_START))
        }
    }

    private fun startServiceCompat(intent: Intent) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }.onFailure {
            RoverRuntimeState.log("STATUS startup could not start ${intent.component?.className}: ${it.stackTraceToString()}")
        }
    }

    private fun hasRuntimePermission(permission: String): Boolean =
        Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun runFirstLaunchProvisioningIfNeeded() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PROVISIONING_ATTEMPTED, false) || firstRunPromptInFlight) return

        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.CAMERA
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }

        firstRunPromptInFlight = true
        if (missing.isNotEmpty()) {
            RoverRuntimeState.log("FIRST RUN requesting runtime permissions=${missing.joinToString()}")
            requestPermissions(missing.toTypedArray(), FIRST_RUN_PERMISSION_REQUEST)
        } else {
            finishRuntimePermissionProvisioning()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != FIRST_RUN_PERMISSION_REQUEST) return

        permissions.forEachIndexed { index, permission ->
            val granted = grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
            RoverRuntimeState.log("FIRST RUN permission $permission granted=$granted")
        }
        ensureConfiguredServices()
        finishRuntimePermissionProvisioning()
    }

    private fun finishRuntimePermissionProvisioning() {
        firstRunPromptInFlight = false
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROVISIONING_ATTEMPTED, true)
            .apply()

        if (Build.VERSION.SDK_INT < 23) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) return

        RoverRuntimeState.log("FIRST RUN requesting battery optimization / Doze exemption")
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(direct) }
            .onFailure { directFailure ->
                RoverRuntimeState.log("FIRST RUN direct Doze exemption request failed: ${directFailure.message}")
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    .onFailure { RoverRuntimeState.log("FIRST RUN battery settings failed: ${it.stackTraceToString()}") }
            }
    }
}
