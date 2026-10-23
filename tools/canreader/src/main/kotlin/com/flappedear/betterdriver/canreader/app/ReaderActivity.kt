package com.flappedear.betterdriver.canreader.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.flappedear.betterdriver.core.ble.FoundBleDevice
import com.flappedear.betterdriver.core.ble.obd.scanObdAdapters
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The reader's only screen (FBBD-319): plain buttons, an internal tool with no design round. Find
 * the adapter, run the checks (car standing, engine on), start and stop the drive log, share the
 * logs. The screen stays on while it is open.
 */
class ReaderActivity : Activity() {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var adapterText: TextView
    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var adapterButtons: LinearLayout
    private var scan: Job? = null

    private val prefs by lazy { getSharedPreferences("reader", MODE_PRIVATE) }

    private val refresh = object : Runnable {
        override fun run() {
            val tail = ReaderRun.log?.tail().orEmpty()
            val state = if (ReaderRun.busy) "running" else "idle"
            val text = "[$state]\n" + tail.takeLast(SHOWN_LINES).joinToString("\n")
            if (logText.text.toString() != text) {
                logText.text = text
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Android 15 draws apps edge to edge: without the system bars' insets the status bar hid the
        // top buttons (0.1.0 on the Pixel 6, 23 Oct 2026).
        ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left + PAD, bars.top + PAD, bars.right + PAD, bars.bottom + PAD)
            insets
        }
        adapterText = TextView(this).apply { textSize = STATUS_SP }
        adapterButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logText = TextView(this).apply { setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(logText) }
        column.addView(TextView(this).apply {
            text = "FR-S reader ${packageManager.getPackageInfo(packageName, 0).versionName}"
            textSize = TITLE_SP
        })
        column.addView(adapterText)
        column.addView(button("Find adapter") { findAdapters() })
        column.addView(adapterButtons)
        column.addView(button("Run checks (standing, engine on, ~6 min)") { start(ReaderRun.What.Checks) })
        column.addView(button("Start drive log") { start(ReaderRun.What.Drive) })
        column.addView(button("Stop") { ReaderRun.stop() })
        column.addView(button("Share logs") { share() })
        column.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(column)
        showAdapter()
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun showAdapter() {
        val name = prefs.getString(KEY_NAME, null)
        val address = prefs.getString(KEY_ADDRESS, "")
        adapterText.text = if (name == null) "Adapter: none chosen" else "Adapter: $name $address"
    }

    private fun findAdapters() {
        if (!askPermissions()) return
        scan?.cancel()
        adapterButtons.removeAllViews()
        adapterText.text = "Searching for OBD adapters…"
        scan = scope.launch {
            var found = emptyList<FoundBleDevice>()
            withTimeoutOrNull(SCAN_MS) { scanObdAdapters(this@ReaderActivity).collect { found = it } }
            if (found.isEmpty()) {
                adapterText.text = "No OBD adapter found. Is it plugged in and the ignition on?"
                return@launch
            }
            showAdapter()
            found.forEach { d ->
                adapterButtons.addView(
                    button("Use ${d.name.ifEmpty { d.address }} (${d.address})") {
                        prefs.edit().putString(KEY_NAME, d.name).putString(KEY_ADDRESS, d.address).apply()
                        adapterButtons.removeAllViews()
                        showAdapter()
                    },
                )
            }
        }
    }

    private fun start(what: ReaderRun.What) {
        val address = prefs.getString(KEY_ADDRESS, null)
        if (address == null) {
            adapterText.text = "Find the adapter first."
            return
        }
        if (!askPermissions()) return
        ReaderRun.start(this, prefs.getString(KEY_NAME, "").orEmpty(), address, what)
    }

    private fun share() {
        val files = ReaderRun.logDir(this).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        if (files.isEmpty()) {
            adapterText.text = "No logs yet."
            return
        }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri(null, uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share FR-S reader logs"))
    }

    /** Asks for what Bluetooth LE needs on this Android version; true when all of it is granted. */
    private fun askPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            val what = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Nearby devices" else "Location"
            adapterText.text = "Allow $what, then press the button again."
            requestPermissions(missing.toTypedArray(), 1)
        }
        return missing.isEmpty()
    }

    private companion object {
        const val KEY_NAME = "adapter_name"
        const val KEY_ADDRESS = "adapter_address"
        const val SCAN_MS = 8_000L
        const val REFRESH_MS = 500L
        const val SHOWN_LINES = 120
        const val PAD = 32
        const val TITLE_SP = 22f
        const val STATUS_SP = 16f
    }
}
