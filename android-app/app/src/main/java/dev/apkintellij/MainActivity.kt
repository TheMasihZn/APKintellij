package dev.apkintellij

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Unpacks Linux on first launch, starts [LinuxService], then shows the IntelliJ desktop through
 * noVNC in a full-window WebView. The VNC server follows the window size, so resizing the DeX
 * window resizes the IDE.
 */
class MainActivity : Activity() {
  private val main = Handler(Looper.getMainLooper())
  private lateinit var linux: Linux
  private lateinit var webView: WebView
  private lateinit var overlay: LinearLayout
  private lateinit var status: TextView
  private lateinit var progress: ProgressBar
  private lateinit var buttons: LinearLayout
  private var viewerShown = false
  private var lastBack = 0L

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    linux = Linux(this)
    buildUi()
    askPermissions()
    boot()
  }

  private fun askPermissions() {
    if (Build.VERSION.SDK_INT >= 33) {
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    } else if (Build.VERSION.SDK_INT < 30) {
      requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
    }
    // Ask for shared storage once, on the first launch, so projects can live on /sdcard.
    if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager() && linux.installedVersion() == null) {
      runCatching {
        startActivity(
          Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        )
      }
    }
  }

  private fun boot() {
    viewerShown = false
    showOverlay("Starting…", indeterminate = true)
    thread(name = "boot") {
      try {
        if (!linux.isInstalled()) {
          Installer(this, linux).install { fraction, text ->
            main.post { showOverlay(text, indeterminate = false, fraction = fraction) }
          }
        }
        main.post { startLinux() }
      } catch (t: Throwable) {
        main.post { showError("Setting up Linux failed:\n$t") }
      }
    }
  }

  private fun startLinux() {
    showOverlay("Starting Linux, the display server and IntelliJ…", indeterminate = true)
    val density = resources.displayMetrics.density
    val width = (window.decorView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) / density
    val height = (window.decorView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) / density
    startForegroundService(
      Intent(this, LinuxService::class.java)
        .putExtra(LinuxService.EXTRA_WIDTH, width.toInt().coerceAtLeast(800))
        .putExtra(LinuxService.EXTRA_HEIGHT, height.toInt().coerceAtLeast(600))
    )
    waitForViewer(System.currentTimeMillis())
  }

  private fun waitForViewer(startedAt: Long) {
    thread(name = "wait-viewer") {
      val up = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", Linux.VIEWER_PORT), 500) }
      }.isSuccess
      main.post {
        when {
          up -> showViewer()
          System.currentTimeMillis() - startedAt > 180_000 || (!LinuxService.running && System.currentTimeMillis() - startedAt > 5_000) ->
            showError("The Linux session did not come up.")
          else -> main.postDelayed({ waitForViewer(startedAt) }, 1000)
        }
      }
    }
  }

  private fun showViewer() {
    viewerShown = true
    overlay.visibility = View.GONE
    webView.loadUrl(
      "http://127.0.0.1:${Linux.VIEWER_PORT}/vnc.html" +
        "?autoconnect=true&resize=remote&reconnect=true&reconnect_delay=1500" +
        "&show_dot=true&path=websockify"
    )
    webView.requestFocus()
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun buildUi() {
    val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    webView = WebView(this).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.setSupportZoom(false)
      isFocusable = true
      isFocusableInTouchMode = true
      webViewClient = object : WebViewClient() {
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
          if (request.isForMainFrame) main.postDelayed({ if (viewerShown) view.reload() }, 2000)
        }
      }
    }
    root.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

    val pad = (24 * resources.displayMetrics.density).toInt()
    overlay = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(pad, pad, pad, pad)
      setBackgroundColor(Color.parseColor("#1E1F22"))
    }
    val title = TextView(this).apply {
      text = getString(R.string.app_name)
      textSize = 24f
      setTextColor(Color.WHITE)
    }
    status = TextView(this).apply {
      setTextColor(Color.parseColor("#BCBEC4"))
      textSize = 14f
      setPadding(0, pad / 2, 0, pad / 2)
      gravity = Gravity.CENTER
    }
    progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
    buttons = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
      addView(Button(context).apply { text = "Retry"; setOnClickListener { restart() } })
      addView(Button(context).apply { text = "Show log"; setOnClickListener { showLog() } })
      addView(Button(context).apply { text = "Reinstall Linux"; setOnClickListener { reinstall() } })
    }
    overlay.addView(title)
    overlay.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    overlay.addView(progress, LinearLayout.LayoutParams((480 * resources.displayMetrics.density).toInt(), WRAP_CONTENT))
    overlay.addView(buttons)
    root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    setContentView(root)
  }

  private fun showOverlay(text: String, indeterminate: Boolean, fraction: Float = 0f) {
    overlay.visibility = View.VISIBLE
    buttons.visibility = View.GONE
    progress.visibility = View.VISIBLE
    progress.isIndeterminate = indeterminate
    progress.progress = (fraction * 1000).toInt()
    status.text = text
  }

  private fun showError(text: String) {
    overlay.visibility = View.VISIBLE
    progress.visibility = View.GONE
    buttons.visibility = View.VISIBLE
    status.text = "$text\n\nThe log usually says why."
  }

  private fun showLog() {
    val text = if (linux.log.isFile) linux.log.readText().takeLast(20_000) else "No log yet."
    val view = ScrollView(this).apply {
      addView(TextView(context).apply {
        this.text = text
        setTextIsSelectable(true)
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(32, 32, 32, 32)
      })
    }
    android.app.AlertDialog.Builder(this).setTitle("Linux log").setView(view)
      .setPositiveButton("Close", null).show()
  }

  private fun restart() {
    startService(Intent(this, LinuxService::class.java).setAction(LinuxService.ACTION_STOP))
    main.postDelayed({ boot() }, 1500)
  }

  private fun reinstall() {
    android.app.AlertDialog.Builder(this)
      .setTitle("Reinstall Linux?")
      .setMessage("This unpacks a fresh copy of Linux and IntelliJ. Anything stored inside Linux (projects in /root, settings) is deleted. Files under /sdcard are kept.")
      .setPositiveButton("Reinstall") { _, _ ->
        startService(Intent(this, LinuxService::class.java).setAction(LinuxService.ACTION_STOP))
        thread { java.io.File(linux.rootfs, ".apkintellij-rootfs").delete(); main.postDelayed({ boot() }, 1500) }
      }
      .setNegativeButton("Cancel", null)
      .show()
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    // In DeX the right mouse button or Esc can arrive as Back; never let that close the IDE.
    if (!viewerShown) return super.onBackPressed()
    val now = System.currentTimeMillis()
    if (now - lastBack < 1500) moveTaskToBack(true) else lastBack = now
  }
}
