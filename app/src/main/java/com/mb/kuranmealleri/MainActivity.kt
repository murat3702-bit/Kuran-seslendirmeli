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
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import java.util.Locale
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
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


    // ---- Sesli okuma: telefonun kendi TTS motoru (Google Konuşma Servisi vb.) ----
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsLangOk = true
    private var ttsQueued: Runnable? = null
    @Volatile private var ttsActive = false
    private var ttsLastId = "0"

    private fun ttsEvent(type: String, id: String) {
        callJs("window.__ttsEvent&&window.__ttsEvent(" + JSONObject.quote(type) + "," + JSONObject.quote(id) + ")")
    }

    private fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(applicationContext) { status ->
            runOnUiThread {
                val t = tts
                if (status != TextToSpeech.SUCCESS || t == null) {
                    ttsQueued = null
                    tts = null
                    stopTts()
                    ttsEvent("error", ttsLastId)
                    return@runOnUiThread
                }
                val r = t.setLanguage(Locale.forLanguageTag("tr-TR"))
                ttsLangOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    // Kimlik biçimi: "<jeton>|<parça no>|<parça sayısı>"; ayetin son parçası bitince sayfaya "done" gider.
                    override fun onDone(utteranceId: String?) {
                        val p = utteranceId?.split("|") ?: return
                        if (p.size == 3 && p[1].toIntOrNull() == (p[2].toIntOrNull() ?: 0) - 1) ttsEvent("done", p[0])
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        val p = utteranceId?.split("|") ?: return
                        ttsEvent("error", p[0])
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        val p = utteranceId?.split("|") ?: return
                        ttsEvent("error", p[0])
                    }
                })
                ttsReady = true
                val q = ttsQueued
                ttsQueued = null
                q?.run()
            }
        }
    }

    /** Çok uzun ayetler TTS giriş sınırını aşmasın diye cümle sınırlarından parçalanır. */
    private fun chunkText(s: String, max: Int = 3000): List<String> {
        if (s.length <= max) return listOf(s)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (part in s.split(Regex("(?<=[.!?;:])\\s+"))) {
            if (sb.isNotEmpty() && sb.length + part.length + 1 > max) {
                out.add(sb.toString()); sb.setLength(0)
            }
            if (part.length > max) {
                part.chunked(max).forEach { out.add(it) }
            } else {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(part)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun speakTts(text: String, rate: Double, id: String) {
        ttsActive = true
        ttsLastId = id
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val t = tts
        if (t == null || !ttsReady) {
            ttsQueued = Runnable { speakTts(text, rate, id) }
            initTts()
            return
        }
        if (!ttsLangOk) {
            stopTts()
            ttsEvent("lang", id)
            return
        }
        t.setSpeechRate(rate.toFloat().coerceIn(0.5f, 3f))
        val parts = chunkText(text)
        parts.forEachIndexed { i, c ->
            t.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$id|$i|${parts.size}")
        }
    }

    private fun stopTts() {
        ttsQueued = null
        ttsActive = false
        tts?.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                            val fallback = "(function(){var c=0;['sheet','backdrop','admin','reader','kvdet','kvlist','article'].forEach(function(i){var e=document.getElementById(i);if(e&&e.classList.contains('on')){e.classList.remove('on');c=1}});return c})()"
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

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        if (!ttsActive) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
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


        /** Sesli okuma: metni telefonun TTS motoruyla okur (hız: 0.5-3.0, id: sayfanın verdiği jeton). */
        @JavascriptInterface
        fun ttsSpeak(text: String, rate: Double, id: String) {
            runOnUiThread { speakTts(text, rate, id) }
        }

        @JavascriptInterface
        fun ttsStop() {
            runOnUiThread { stopTts() }
        }

        /** Ayet ekranı karartma yoğunluğu (0-80, yüzde). Hiç kaydedilmemişse -1 döner. */
        @JavascriptInterface
        fun getDim(): Int =
            getSharedPreferences("km_prefs", Context.MODE_PRIVATE).getInt("view_dim", -1)

        @JavascriptInterface
        fun setDim(percent: Int) {
            getSharedPreferences("km_prefs", Context.MODE_PRIVATE)
                .edit().putInt("view_dim", percent.coerceIn(0, 80)).apply()
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
