package com.mb.kuranmealleri

import android.annotation.SuppressLint
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import java.io.IOException
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MAX_BACKUP_BYTES = 25 * 1024 * 1024

        /** Yedeklerin otomatik yazıldığı klasör: <dahili depolama>/Documents/Kuran Mealleri Yedek */
        private const val BACKUP_PARENT = "Documents"
        private const val BACKUP_DIR = "Kuran Mealleri Yedek"
    }

    /** Yedek yükleme penceresini yedek klasöründe açar (SAF'ın başlangıç konumu). */
    private class OpenBackupContract : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).apply {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments%2FKuran%20Mealleri%20Yedek")
                )
            }
    }

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    // Yönetici panelindeki "resim seç" için dosya seçici sonucu
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uris = WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    // ---- Yedekleme: Storage Access Framework (izin gerektirmez) ----
    private var pendingBackup: String? = null

    // Android 8-9 (API 26-28): ortak klasöre yazmak için depolama izni gerekir; Android 10+ için gerekmez.
    private var pendingFolderSave: Pair<String, String>? = null
    private val requestStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val p = pendingFolderSave
            pendingFolderSave = null
            if (p == null) return@registerForActivityResult
            if (granted) saveToBackupFolder(p.first, p.second) else saveWithPicker(p.first, p.second)
        }

    /** Yedeği "Documents/Kuran Mealleri Yedek" klasörüne yazar; klasör yoksa oluşturulur. Başarısız olursa dosya penceresine döner. */
    private fun saveToBackupFolder(json: String, fileName: String) {
        Thread {
            try {
                val where = writeToBackupFolder(json.toByteArray(Charsets.UTF_8), fileName)
                callJs("window.__onBackupSaved&&window.__onBackupSaved(true," + JSONObject.quote(where) + ")")
            } catch (e: Exception) {
                runOnUiThread { saveWithPicker(json, fileName) }
            }
        }.start()
    }

    private fun writeToBackupFolder(bytes: ByteArray, fileName: String): String {
        val where = "$BACKUP_PARENT/$BACKUP_DIR"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, where)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values) ?: throw IOException("Dosya oluşturulamadı")
            try {
                contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    ?: throw IOException("Dosya açılamadı")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), BACKUP_DIR)
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Klasör oluşturulamadı")
            val dot = fileName.lastIndexOf('.')
            val base = if (dot > 0) fileName.substring(0, dot) else fileName
            val ext = if (dot > 0) fileName.substring(dot) else ""
            var f = File(dir, fileName)
            var n = 1
            while (f.exists()) f = File(dir, "$base ($n)$ext").also { n++ }
            f.writeBytes(bytes)
            MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf("application/json"), null)
        }
        return where
    }

    /** Yedek klasörüne yazılamazsa sistemin dosya kaydetme penceresi açılır. */
    private fun saveWithPicker(json: String, safeName: String) {
        pendingBackup = json
        try {
            createBackupDoc.launch(safeName)
        } catch (e: Exception) {
            pendingBackup = null
            callJs("window.__onBackupSaved&&window.__onBackupSaved(false," + JSONObject.quote(e.message ?: "dosya penceresi açılamadı") + ")")
        }
    }

    /** "Yedekle (Dosya Kaydet)": sistemin dosya kaydetme penceresi (ACTION_CREATE_DOCUMENT). */
    private val createBackupDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val text = pendingBackup
            pendingBackup = null
            if (uri == null || text == null) {
                callJs("window.__onBackupSaved&&window.__onBackupSaved(false,'cancel')")
                return@registerForActivityResult
            }
            Thread {
                try {
                    val out = contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Dosya açılamadı")
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    callJs("window.__onBackupSaved&&window.__onBackupSaved(true,'')")
                } catch (e: Exception) {
                    callJs("window.__onBackupSaved&&window.__onBackupSaved(false," + JSONObject.quote(e.message ?: "bilinmeyen hata") + ")")
                }
            }.start()
        }

    /** "Yedek Dosyası Yükle": sistemin dosya seçicisi (ACTION_OPEN_DOCUMENT). */
    private val openBackupDoc =
        registerForActivityResult(OpenBackupContract()) { uri ->
            if (uri == null) {
                callJs("window.__onBackupPicked&&window.__onBackupPicked(null,'cancel')")
                return@registerForActivityResult
            }
            Thread {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IOException("Dosya açılamadı")
                    if (bytes.size > MAX_BACKUP_BYTES) throw IOException("Dosya çok büyük")
                    val text = String(bytes, Charsets.UTF_8)
                    callJs("window.__onBackupPicked&&window.__onBackupPicked(" + JSONObject.quote(text) + ")")
                } catch (e: Exception) {
                    callJs("window.__onBackupPicked&&window.__onBackupPicked(null," + JSONObject.quote(e.message ?: "bilinmeyen hata") + ")")
                }
            }.start()
        }

    private fun callJs(script: String) {
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(script, null) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)

        // İçerik https://appassets.androidplatform.net/assets/... adresinden, cihaz içinden sunulur.
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true      // yer imleri, notlar ve ayarlar (localStorage) burada saklanır
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
        }
        applyDark()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: Exception) {
                }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    picker.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            private fun exitApp() {
                // Android 12+ ana ekranda geri tuşu uygulamayı öldürmez, arka plana atar (aktivite yaşar).
                // Bu yüzden işleyiciyi kalıcı kapatmayız: varsayılan davranış çalıştıktan hemen sonra tekrar açarız.
                // Aksi halde uygulama yeniden açıldığında geri tuşu web katmanına hiç uğramadan uygulamayı kapatırdı.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }

            override fun handleOnBackPressed() {
                // 1 = web katmanı geri tuşunu işledi, 0 = ana ekrandayız (çık), -1 = işleyici yok/hata verdi
                val js = "(function(){try{return window.__androidBack?(window.__androidBack()?1:0):-1}catch(e){return -1}})()"
                web.evaluateJavascript(js) { r ->
                    when (r?.trim('"')) {
                        "1" -> Unit
                        "0" -> exitApp()
                        else -> {
                            // Yedek: açık olan katmanı (menü, okuyucu, makale) doğrudan kapat; hiçbiri açık değilse çık
                            val fallback = "(function(){var c=0;['sheet','backdrop','admin','reader','article'].forEach(function(i){var e=document.getElementById(i);if(e&&e.classList.contains('on')){e.classList.remove('on');c=1}});return c})()"
                            web.evaluateJavascript(fallback) { r2 -> if (r2?.trim('"') != "1") exitApp() }
                        }
                    }
                }
            }
        })

        web.loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/www/index.html")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyDark()
    }


    // ---------------- Sesli okuma (Android'in kendi Türkçe konuşma motoru) ----------------
    private var tts: TextToSpeech? = null
    private var ttsState = 0   // 0=başlamadı 1=hazırlanıyor 2=hazır 3=Türkçe ses yok 4=motor yok
    private var pendingTts: Triple<String, String, Double>? = null

    private fun ttsEvent(ev: String, id: String) {
        runOnUiThread {
            callJs("window.__tts&&window.__tts('" + ev + "','" + id.replace("'", "") + "')")
        }
    }

    private fun ttsEnsure() {
        if (tts != null) return
        ttsState = 1
        tts = TextToSpeech(applicationContext) { status ->
            runOnUiThread {
                if (status != TextToSpeech.SUCCESS) {
                    ttsState = 4; pendingTts = null
                    ttsEvent("noengine", ""); return@runOnUiThread
                }
                val r = tts?.setLanguage(Locale("tr", "TR")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ttsState = 3; pendingTts = null
                    ttsEvent("nolang", ""); return@runOnUiThread
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { ttsEvent("start", utteranceId ?: "") }
                    override fun onDone(utteranceId: String?) { ttsEvent("done", utteranceId ?: "") }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { ttsEvent("error", utteranceId ?: "") }
                    override fun onError(utteranceId: String?, errorCode: Int) { ttsEvent("error", utteranceId ?: "") }
                })
                ttsState = 2
                pendingTts?.let { ttsSpeakNow(it.first, it.second, it.third) }
                pendingTts = null
            }
        }
    }

    private fun ttsSpeakNow(id: String, text: String, rate: Double) {
        val t = tts ?: return
        t.setSpeechRate(rate.toFloat())
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun ttsReset() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null; ttsState = 0; pendingTts = null
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        if (ttsState == 3 || ttsState == 4) ttsReset()
    }

    override fun onPause() {
        try { tts?.stop() } catch (_: Exception) {}
        pendingTts = null
        if (ttsState == 2) ttsEvent("paused", "")
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        ttsReset()
        super.onDestroy()
    }

    /** Sistem koyu temadaysa sayfadaki prefers-color-scheme: dark devreye girsin. */
    @Suppress("DEPRECATION")
    private fun applyDark() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, false)
        } else {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(
                    web.settings,
                    if (night) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                )
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(
                    web.settings,
                    WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY
                )
            }
        }
    }

    /** Sayfadan çağrılan yerel işlevler (paylaş, kopyala, durum/gezinme çubuğu rengi). */
    inner class Bridge {
        @JavascriptInterface
        fun ttsSpeak(id: String, text: String, rate: Double) {
            runOnUiThread {
                when (ttsState) {
                    0 -> { pendingTts = Triple(id, text, rate); ttsEnsure() }
                    1 -> pendingTts = Triple(id, text, rate)
                    2 -> ttsSpeakNow(id, text, rate)
                    3 -> ttsEvent("nolang", "")
                    else -> ttsEvent("noengine", "")
                }
            }
        }

        @JavascriptInterface
        fun ttsStop() {
            runOnUiThread { pendingTts = null; try { tts?.stop() } catch (_: Exception) {} }
        }

        @JavascriptInterface
        fun ttsOpenSettings() {
            runOnUiThread {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) {
                    try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                }
            }
        }

        @JavascriptInterface
        fun ttsInstallData() {
            runOnUiThread {
                try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) { }
            }
        }

        @JavascriptInterface
        fun share(text: String) {
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(send, null))
            }
        }

        @JavascriptInterface
        fun copy(text: String): Boolean {
            runOnUiThread {
                val cm = this@MainActivity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ayet", text))
            }
            return true
        }

        /** Yedek metnini "Documents/Kuran Mealleri Yedek" klasörüne .json dosyası olarak kaydeder (klasör otomatik oluşur). */
        @JavascriptInterface
        fun saveBackup(json: String, fileName: String) {
            val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "kuran_uygulamasi_yedek.json" }
            runOnUiThread {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingFolderSave = json to safe
                    try {
                        requestStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } catch (e: Exception) {
                        pendingFolderSave = null
                        saveWithPicker(json, safe)
                    }
                } else {
                    saveToBackupFolder(json, safe)
                }
            }
        }

        /** Dosya seçiciyi açar; seçilen yedeğin metnini sayfaya iletir (doğrulama sayfada yapılır). */
        @JavascriptInterface
        fun pickBackup() {
            runOnUiThread {
                try {
                    openBackupDoc.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                } catch (e: Exception) {
                    callJs("window.__onBackupPicked&&window.__onBackupPicked(null," + JSONObject.quote(e.message ?: "dosya seçici açılamadı") + ")")
                }
            }
        }

        @Suppress("DEPRECATION")
        @JavascriptInterface
        fun setBars(cssColor: String) {
            val m = Regex("""rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)""").find(cssColor) ?: return
            val (r, g, b) = m.destructured
            val color = Color.rgb(r.toInt(), g.toInt(), b.toInt())
            runOnUiThread {
                window.statusBarColor = color
                window.navigationBarColor = color
                web.setBackgroundColor(color)
                val light = ColorUtils.calculateLuminance(color) > 0.5
                val ctl = WindowInsetsControllerCompat(window, window.decorView)
                ctl.isAppearanceLightStatusBars = light
                ctl.isAppearanceLightNavigationBars = light
            }
        }
    }
}
