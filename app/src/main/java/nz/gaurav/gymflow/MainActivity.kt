package nz.gaurav.gymflow

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

/** Hosts the GymFlow web app and gives it native powers through the "Android" bridge. */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var pageReady = false
    private var pendingAlert = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = fileCallback
            fileCallback = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val bg = Color.parseColor("#0E1014")
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        web = WebView(this).apply {
            setBackgroundColor(bg)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    if (pendingAlert) {
                        pendingAlert = false
                        js("nativeAlert()")
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    val accept = params?.acceptTypes?.joinToString(",") ?: ""
                    val pick = Intent(Intent.ACTION_GET_CONTENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(if (accept.contains("audio")) "audio/*" else "*/*")
                    return try {
                        pickFile.launch(Intent.createChooser(pick, null)); true
                    } catch (e: Exception) {
                        fileCallback = null; false
                    }
                }
            }
        }
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // Keep the page clear of the status bar, navigation bar and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        // Back button: let the app go back a screen; leave the app only from Home.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!pageReady) { moveTaskToBack(true); return }
                web.evaluateJavascript("(window.handleBack&&handleBack())?'1':'0'") { r ->
                    if (r == null || !r.contains("1")) moveTaskToBack(true)
                }
            }
        })

        web.loadUrl("file:///android_asset/gymflow.html")
        handleAlertIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlertIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        getSystemService(NotificationManager::class.java).cancel(Rest.ALERT_ID)
        if (pageReady) js("window.nativeRestSync&&nativeRestSync()")
    }

    override fun onPause() {
        super.onPause()
        foreground = false
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun js(code: String) {
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(code, null) }
    }

    /** Opened by the rest-over alert: show over the lock screen and turn the screen on. */
    private fun handleAlertIntent(i: Intent?) {
        if (i == null || !i.getBooleanExtra(EXTRA_ALERT, false)) return
        i.removeExtra(EXTRA_ALERT)
        setLockFlags(true)
        if (pageReady) js("nativeAlert()") else pendingAlert = true
        root.postDelayed({ setLockFlags(false) }, 8000)
    }

    private fun setLockFlags(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runOnUiThread { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
        }
    }

    /** Everything the web app can ask Android to do. */
    inner class Bridge {
        @JavascriptInterface
        fun keepAwake(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun setDim(on: Boolean) {
            runOnUiThread {
                val lp = window.attributes
                lp.screenBrightness = if (on) 0.03f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }

        @JavascriptInterface
        fun setTheme(bg: String, light: Boolean) {
            runOnUiThread {
                val c = try { Color.parseColor(bg) } catch (e: Exception) { Color.BLACK }
                root.setBackgroundColor(c)
                web.setBackgroundColor(c)
                val ctl = WindowInsetsControllerCompat(window, window.decorView)
                ctl.isAppearanceLightStatusBars = light
                ctl.isAppearanceLightNavigationBars = light
            }
        }

        @JavascriptInterface
        fun workoutStart(name: String, start: Double) {
            Rest.setWorkout(this@MainActivity, name, start.toLong())
            askNotifications()
        }

        @JavascriptInterface
        fun workoutEnd() = Rest.clearWorkout(this@MainActivity)

        @JavascriptInterface
        fun startRest(endAt: Double, label: String) = Rest.start(this@MainActivity, endAt.toLong(), label)

        @JavascriptInterface
        fun cancelRest() = Rest.cancel(this@MainActivity)

        @JavascriptInterface
        fun restEnd(): Double = Rest.prefs(this@MainActivity).getLong(Rest.K_END, 0L).toDouble()

        @JavascriptInterface
        fun vibrate(on: Boolean) {
            Rest.prefs(this@MainActivity).edit().putBoolean(Rest.K_VIBRATE, on).apply()
        }

        @JavascriptInterface
        fun setSound(b64: String) {
            try {
                File(filesDir, Rest.SOUND_FILE).writeBytes(Base64.decode(b64, Base64.DEFAULT))
            } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun share(name: String, mime: String, b64: String) {
            runOnUiThread {
                try {
                    val dir = File(cacheDir, "share").apply { mkdirs() }
                    val f = File(dir, name.replace("/", "_"))
                    f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType(mime)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, name))
                } catch (e: Exception) { }
            }
        }

        @JavascriptInterface
        fun fsiNeeded(): Boolean =
            Build.VERSION.SDK_INT >= 34 &&
                !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

        @JavascriptInterface
        fun openFsiSettings() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                        )
                    } catch (e: Exception) { }
                }
            }
        }
    }

    companion object {
        const val EXTRA_ALERT = "rest_alert"
        @Volatile var instance: MainActivity? = null
        @Volatile var foreground = false
    }
}
