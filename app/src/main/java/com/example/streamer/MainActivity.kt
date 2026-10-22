package com.example.streamer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONTokener
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.content.ContentValues
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var urlBox: EditText
    private lateinit var dl: Button
    private lateinit var shieldBtn: Button
    private lateinit var seriesBtn: Button
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var sniffed: String? = null
    @Volatile private var sniffedHeaders: Map<String, String> = emptyMap()
    private val m3u8s = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile private var remuxInfo = ""
    @Volatile private var lastReport = ""
    private var allowedSite = ""
    private var shield = true
    private var ua = ""
    private var busy = false

    private val blocked = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "adnxs.com", "popads.net", "popcash.net",
        "propellerads.com", "onclickads.net", "exoclick.com", "exosrv.com",
        "juicyads.com", "trafficjunky.com", "adsterra.com", "highperformanceformat.com",
        "revcontent.com", "taboola.com", "outbrain.com", "mgid.com", "adcash.com",
        "clickadu.com", "hilltopads.net", "ad-maven.com", "admaven.com",
        "pushwoosh.com", "zeropark.com", "richpush.co", "a-ads.com", "yllix.com",
        "popunder.net", "tsyndicate.com", "trafficstars.com", "realsrv.com",
        "acscdn.com", "profitablecpmrate.com", "effectivegatecpm.com", "pubadx.one"
    )

    private val shieldJs = "(function(){window.open=function(){return null};" +
        "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[target=_blank]'):null;" +
        "if(a){e.preventDefault();e.stopPropagation();}},true);})();"

    private fun siteOf(host: String?): String {
        val p = (host ?: "").split(".")
        return if (p.size >= 2) p.takeLast(2).joinToString(".") else host ?: ""
    }

    private fun isBlocked(host: String?) =
        host != null && blocked.any { host == it || host.endsWith(".$it") }

    // Glossy aqua orb with a play symbol: the Unistream logo mark.
    private class LogoOrb(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f - 2f
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.4f, r * 1.5f,
                intArrayOf(0xFFB9F6FF.toInt(), 0xFF35B6F2.toInt(), 0xFF1766C9.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            p.color = 0xCCFFFFFF.toInt()
            c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, cy - r, 0f, cy, 0xCCFFFFFF.toInt(), 0x11FFFFFF, Shader.TileMode.CLAMP)
            c.drawOval(RectF(cx - r * 0.8f, cy - r * 0.95f, cx + r * 0.8f, cy - r * 0.05f), p)
            p.shader = null
            p.color = Color.WHITE
            val tri = Path()
            tri.moveTo(cx - r * 0.25f, cy - r * 0.42f)
            tri.lineTo(cx - r * 0.25f, cy + r * 0.42f)
            tri.lineTo(cx + r * 0.47f, cy)
            tri.close()
            c.drawPath(tri, p)
        }
    }

    // Soft translucent bubbles drawn behind the interface.
    private class Bubbles(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val spec = arrayOf(
            floatArrayOf(0.08f, 0.06f, 26f), floatArrayOf(0.30f, 0.03f, 14f), floatArrayOf(0.62f, 0.07f, 34f),
            floatArrayOf(0.90f, 0.04f, 20f), floatArrayOf(0.15f, 0.30f, 40f), floatArrayOf(0.80f, 0.35f, 28f),
            floatArrayOf(0.45f, 0.55f, 18f), floatArrayOf(0.10f, 0.75f, 32f), floatArrayOf(0.70f, 0.80f, 44f),
            floatArrayOf(0.92f, 0.92f, 22f), floatArrayOf(0.35f, 0.93f, 36f)
        )
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            for (b in spec) {
                val cx = b[0] * width
                val cy = b[1] * height
                val r = b[2] * d
                p.style = Paint.Style.FILL
                p.shader = RadialGradient(cx, cy, r,
                    intArrayOf(0x08FFFFFF, 0x14FFFFFF, 0x70FFFFFF),
                    floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r, p)
                p.shader = null
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f
                p.color = 0x55FFFFFF
                c.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.FILL
                p.color = 0x66FFFFFF
                c.drawOval(RectF(cx - r * 0.55f, cy - r * 0.78f, cx - r * 0.05f, cy - r * 0.42f), p)
            }
        }
    }

    private lateinit var progressBar: ProgressBar

    // ---- Dollars-saved counter (rough estimate; tweak these two numbers) ----
    private val RATE_PER_MIN = 0.04   // ~ $4.99 rental per 2 hours of video
    private val MB_PER_MIN = 10.0     // ~ typical 480p stream, used for direct file downloads
    private lateinit var savedLabel: TextView
    private val prefs by lazy { getSharedPreferences("stats", MODE_PRIVATE) }
    private val myDownloads = HashSet<Long>()

    private fun savedTotal() = prefs.getFloat("saved", 0f)

    private fun refreshSaved() {
        savedLabel.text = String.format(java.util.Locale.US, "Dollars saved: \$%.2f", savedTotal())
    }

    private fun addSaved(d: Double) {
        prefs.edit().putFloat("saved", (savedTotal() + d).toFloat()).apply()
        runOnUiThread { refreshSaved() }
    }

    private val dlDone = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id !in myDownloads) return
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
                if (cur.moveToFirst()) {
                    val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val bytes = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    if (status == DownloadManager.STATUS_SUCCESSFUL && bytes > 0) {
                        addSaved(bytes / 1048576.0 / MB_PER_MIN * RATE_PER_MIN)
                    }
                }
            }
        }
    }


    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun glossy(top: Int, bottom: Int, radiusDp: Int = 20): Drawable {
        val r = dp(radiusDp).toFloat()
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = r
            setStroke(dp(1), 0xCCFFFFFF.toInt())
        }
        val shine = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xC8FFFFFF.toInt(), 0x20FFFFFF)).apply {
            cornerRadii = floatArrayOf(r, r, r, r, r / 2, r / 2, r / 2, r / 2)
        }
        return LayerDrawable(arrayOf(base, shine)).apply {
            setLayerInset(1, dp(4), dp(2), dp(4), 0)
            setLayerHeight(1, dp(17))
            setLayerGravity(1, Gravity.TOP)
        }
    }

    private fun paint(b: Button, top: Int, bottom: Int) {
        b.background = glossy(top, bottom)
        b.setTextColor(Color.WHITE)
        b.isAllCaps = false
        b.textSize = 13f
        b.setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
        b.minWidth = 0; b.minimumWidth = 0; b.minHeight = 0; b.minimumHeight = 0
        b.setPadding(dp(14), dp(8), dp(14), dp(8))
        b.stateListAnimator = null
        b.elevation = dp(3).toFloat()
    }

    private fun paintShield() {
        shieldBtn.text = if (shield) "Shield: ON" else "Shield: OFF"
        if (shield) paint(shieldBtn, 0xFF9BE564.toInt(), 0xFF2FA21F.toInt())
        else paint(shieldBtn, 0xFFFFC46B.toInt(), 0xFFE67E1F.toInt())
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        window.statusBarColor = 0xFF3DB2F5.toInt()
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF1E90E8.toInt(), 0xFF5CC8FF.toInt(), 0xFF8EE27A.toInt()))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xDDFFFFFF.toInt(), 0x77AEE8FF.toInt())).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0xFFFFFFFF.toInt())
            }
        }
        urlBox = EditText(this).apply {
            hint = "Paste a link..."
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 14f
            setTextColor(0xFF124E78.toInt())
            setHintTextColor(0xFF7BA3BF.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(0xEEFFFFFF.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFF7CC4EE.toInt())
            }
        }
        val go = Button(this).apply { text = "Go" }
        dl = Button(this).apply { text = "Save" }
        shieldBtn = Button(this)
        seriesBtn = Button(this).apply { text = "Series" }
        paint(go, 0xFF9BE564.toInt(), 0xFF2FA21F.toInt())
        paint(dl, 0xFF6FD3FF.toInt(), 0xFF1B7FD6.toInt())
        paintSeries()
        paintShield()
        val bp = { LinearLayout.LayoutParams(-2, dp(40)).apply { setMargins(dp(4), 0, 0, 0) } }
        bar.addView(urlBox, LinearLayout.LayoutParams(0, dp(40), 1f))
        bar.addView(go, bp())
        bar.addView(dl, bp())
        bar.addView(seriesBtn, bp())
        bar.addView(shieldBtn, bp())

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(0xFF7ED957.toInt())
            visibility = View.GONE
        }

        web = WebView(this).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                }
            }
            clipToOutline = true
        }
        savedLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(3f, 0f, 1f, 0xAA0B4F7A.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = glossy(0xFF9BE564.toInt(), 0xFF2FA21F.toInt(), 14)
            setOnLongClickListener {
                prefs.edit().putFloat("saved", 0f).apply()
                refreshSaved()
                toast("Counter reset")
                true
            }
        }
        refreshSaved()
        val wordmark = TextView(this).apply {
            val sp = android.text.SpannableString("Unistream")
            sp.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, 3, 0)
            text = sp
            textSize = 28f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 3f, 0xCC0B4F7A.toInt())
        }
        val tagline = TextView(this).apply {
            text = "stream · save · enjoy"
            textSize = 11f
            setTextColor(0xEEFFFFFF.toInt())
            setShadowLayer(4f, 0f, 1f, 0xAA0B4F7A.toInt())
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            addView(wordmark)
            addView(tagline)
        }
        val logoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
            addView(LogoOrb(this@MainActivity), LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(titleCol)
        }
        col.addView(logoRow)
        col.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(2), dp(8), 0) })
        col.addView(savedLabel, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(6), dp(8), 0) })
        col.addView(progressBar, LinearLayout.LayoutParams(-1, dp(4)))
        val frame = FrameLayout(this).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xAAFFFFFF.toInt(), 0x55BFF0FF.toInt())).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xCCFFFFFF.toInt())
            }
            addView(web, FrameLayout.LayoutParams(-1, -1))
        }
        col.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(8), dp(6), dp(8), dp(8)) })
        root.addView(Bubbles(this), FrameLayout.LayoutParams(-1, -1))
        root.addView(col)
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        ua = web.settings.userAgentString

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url
                if (shield && isBlocked(u.host)) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
                val s = u.toString().lowercase()
                if (Regex("\\.(mp4|webm|m3u8|mpd)(\\?|$)").containsMatchIn(s)) {
                    sniffed = u.toString()
                    sniffedHeaders = HashMap<String, String>(r.requestHeaders)
                    if (s.contains(".m3u8") && !m3u8s.contains(u.toString())) m3u8s.add(u.toString())
                }
                return null
            }

            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.scheme != "http" && u.scheme != "https") return true
                if (!shield) return false
                if (isBlocked(u.host)) return true
                // Strict: the main page may only navigate within the site you typed in.
                if (r.isForMainFrame && siteOf(u.host) != allowedSite) {
                    toast("Blocked redirect to ${u.host}")
                    return true
                }
                return false
            }

            override fun onPageStarted(v: WebView, url: String, f: android.graphics.Bitmap?) {
                urlBox.setText(url)
                if (shield) v.evaluateJavascript(shieldJs, null)
            }

            override fun onPageFinished(v: WebView, url: String) {
                if (shield) v.evaluateJavascript(shieldJs, null)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(v: WebView, d: Boolean, g: Boolean, m: android.os.Message) = false

            override fun onProgressChanged(v: WebView, p: Int) {
                progressBar.progress = p
                progressBar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                customView = view; customCb = cb
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customCb?.onCustomViewHidden()
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }

        web.setDownloadListener { url, _, _, mime, _ -> startDownload(url, mime) }

        go.setOnClickListener { load(urlBox.text.toString()) }
        urlBox.setOnEditorActionListener { _, _, _ -> load(urlBox.text.toString()); true }
        shieldBtn.setOnClickListener {
            shield = !shield
            paintShield()
        }
        seriesBtn.setOnClickListener {
            if (queueActive) stopQueue() else scanEpisodes()
        }
        dl.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("Last download report")
                .setMessage(if (lastReport.isEmpty()) "No HLS download yet." else lastReport)
                .setPositiveButton("OK", null)
                .show()
            true
        }
        dl.setOnClickListener {
            val s = sniffed
            when {
                s == null -> toast("No video detected yet. Press play first.")
                s.lowercase().contains(".m3u8") -> downloadHls(s, web.url)
                s.lowercase().contains(".mpd") -> toast("DASH (.mpd) downloads are not supported yet.")
                else -> startDownload(s, null)
            }
        }

        val f = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(dlDone, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(dlDone, f)

        val shared = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        shared?.let { load(it) }
    }

    private fun load(input: String) {
        var u = input.trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        sniffed = null
        sniffedHeaders = emptyMap()
        m3u8s.clear()
        allowedSite = siteOf(Uri.parse(u).host)
        web.loadUrl(u)
    }

    private fun ensureStorage(): Boolean {
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            toast("Allow storage access, then tap Save again.")
            return false
        }
        return true
    }

    private fun startDownload(url: String, mime: String?, base: String? = null) {
        if (!ensureStorage()) return
        try {
            val guess = URLUtil.guessFileName(url, null, mime)
            val name = if (base != null) base + "." + guess.substringAfterLast('.', "mp4") else guess
            val req = DownloadManager.Request(Uri.parse(url))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            req.addRequestHeader("User-Agent", ua)
            req.addRequestHeader("Referer", web.url ?: url)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            myDownloads.add((getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req))
            toast("Downloading $name")
        } catch (e: Exception) {
            toast("Download failed: ${e.message}")
        }
    }

    // ---------- HLS (.m3u8) downloader ----------

    private class Seg(val url: String, val key: ByteArray?, val iv: ByteArray?, val range: String? = null)

    private fun open(url: String, ref: String?, range: String? = null): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua)
        ref?.let { c.setRequestProperty("Referer", it) }
        // Reuse the exact headers the page's player sent (Referer, Origin, etc.)
        for ((k, v) in sniffedHeaders) {
            if (k.equals("Accept-Encoding", true) || k.equals("Range", true) || k.equals("Host", true)) continue
            c.setRequestProperty(k, v)
        }
        CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
        range?.let { c.setRequestProperty("Range", it) }
        val code = c.responseCode
        if (code !in 200..299) throw Exception("HTTP $code from ${URL(url).host}")
        return c.inputStream
    }

    private fun text(url: String, ref: String?) = open(url, ref).bufferedReader().use { it.readText() }
    private fun absUrl(base: String, rel: String) = URL(URL(base), rel).toString()
    private fun attr(line: String, name: String) =
        Regex("$name=(\"[^\"]*\"|[^,]*)").find(line)?.groupValues?.get(1)?.trim()?.trim('"')

    private fun hexIv(s: String): ByteArray {
        val h = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun seqIv(seq: Long) = ByteArray(16).also { b ->
        for (i in 0 until 8) b[15 - i] = (seq shr (8 * i)).toByte()
    }

    private fun openOut(name: String, mime: String): Pair<OutputStream, () -> Unit> {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
            val out = contentResolver.openOutputStream(uri)!!
            return Pair(out) {
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                contentResolver.update(uri, done, null, null)
            }
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        return Pair(FileOutputStream(File(dir, name))) {}
    }

    private class Fetched(val file: File, val fmp4: Boolean, val secs: Double, val stats: String)

    // Some sites prepend fake image bytes to every .ts segment. Find where the real TS data starts.
    private fun tsStart(d: ByteArray): Int {
        val s = 0x47.toByte()
        if (d.size > 376 && d[0] == s && d[188] == s && d[376] == s) return 0
        val lim = minOf(d.size - 377, 16384)
        for (i in 0 until lim) if (d[i] == s && d[i + 188] == s && d[i + 376] == s) return i
        return -1
    }

    // Downloads all segments of one media playlist into a single temp file.
    private fun fetchMedia(plUrl: String, pl: String, ref: String?, dest: File, tag: String): Fetched {
        var seq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(pl)?.groupValues?.get(1)?.toLong() ?: 0L
        var key: ByteArray? = null
        var ivAttr: String? = null
        var initUrl: String? = null
        var initRange: String? = null
        var nextRange: String? = null
        var lastEnd = 0L
        fun rangeOf(spec: String): String {
            val p = spec.trim().split("@")
            val len = p[0].toLong()
            val off = if (p.size > 1) p[1].toLong() else lastEnd
            lastEnd = off + len
            return "bytes=$off-${off + len - 1}"
        }
        val segs = ArrayList<Seg>()
        for (raw in pl.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    val m = attr(line, "METHOD")
                    if (m == "AES-128") {
                        val ku = attr(line, "URI") ?: throw Exception("Key URI missing")
                        key = open(absUrl(plUrl, ku), ref).use { it.readBytes() }
                        ivAttr = attr(line, "IV")
                    } else if (m == "NONE") { key = null; ivAttr = null }
                    else throw Exception("Unsupported encryption (DRM)")
                }
                line.startsWith("#EXT-X-MAP") -> {
                    initUrl = attr(line, "URI")?.let { absUrl(plUrl, it) }
                    initRange = attr(line, "BYTERANGE")?.let { rangeOf(it) }
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> nextRange = rangeOf(line.substringAfter(':'))
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val iv = if (key != null) (ivAttr?.let { hexIv(it) } ?: seqIv(seq)) else null
                    segs.add(Seg(absUrl(plUrl, line), key, iv, nextRange))
                    nextRange = null
                    seq++
                }
            }
        }
        if (segs.isEmpty()) throw Exception("No segments found")
        val secs = Regex("#EXTINF:([0-9.]+)").findAll(pl).map { it.groupValues[1].toDouble() }.sum()
        var nonTs = 0
        var stripped = 0
        var tiny = 0
        var bytes = 0L
        FileOutputStream(dest).use { o ->
            initUrl?.let { u -> open(u, ref, initRange).use { it.copyTo(o) } }
            for ((i, sg) in segs.withIndex()) {
                var data: ByteArray? = null
                var tries = 0
                while (data == null) {
                    try { data = open(sg.url, ref, sg.range).use { it.readBytes() } }
                    catch (e: Exception) { if (++tries >= 3) throw e; Thread.sleep(800) }
                }
                if (sg.key != null && sg.iv != null) {
                    val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(sg.key, "AES"), IvParameterSpec(sg.iv))
                    data = c.doFinal(data)
                }
                var off = 0
                if (initUrl == null) {
                    val st = tsStart(data)
                    if (st < 0) nonTs++ else off = st
                    if (off > 0) stripped++
                }
                if (data.size < 2000) tiny++
                bytes += data.size - off
                o.write(data, off, data.size - off)
                val pct = (i + 1) * 100 / segs.size
                runOnUiThread { dl.text = "$tag$pct%" }
            }
        }
        val stats = "$tag: ${segs.size} segs, ${bytes / 1024} KB, encrypted=${segs[0].key != null}, " +
            "junk-stripped=$stripped, notTS=$nonTs, tiny=$tiny, ranges=${segs.count { it.range != null }}"
        return Fetched(dest, initUrl != null, secs, stats)
    }

    private fun downloadHls(start: String, ref: String?, base: String? = null, onDone: ((Boolean) -> Unit)? = null) {
        if (busy) { toast("A download is already running."); onDone?.invoke(false); return }
        if (!ensureStorage()) { onDone?.invoke(false); return }
        busy = true
        toast("HLS download started. Keep the app open.")
        val candidates = (m3u8s.toList() + start).distinct()
        Thread {
            var ok = false
            try {
                // Prefer a master playlist: it also tells us where the separate audio track is.
                var masterUrl = start
                var masterText: String? = null
                for (c in candidates) {
                    try {
                        val t = text(c, ref)
                        if (t.contains("#EXT-X-STREAM-INF")) { masterUrl = c; masterText = t; break }
                    } catch (e: Exception) {}
                }
                var plUrl = masterUrl
                var pl = masterText ?: text(masterUrl, ref)
                var audioUrl: String? = null
                if (pl.contains("#EXT-X-STREAM-INF")) {
                    val lines = pl.lines()
                    var best = -1L
                    var bestUrl: String? = null
                    var group: String? = null
                    for (i in lines.indices) {
                        if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                            val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLong() ?: 0L
                            val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                            if (next != null && bw > best) { best = bw; bestUrl = next.trim(); group = attr(lines[i], "AUDIO") }
                        }
                    }
                    if (group != null) {
                        val media = lines.filter {
                            it.startsWith("#EXT-X-MEDIA") && attr(it, "TYPE") == "AUDIO" &&
                                attr(it, "GROUP-ID") == group && attr(it, "URI") != null
                        }
                        val pick = media.firstOrNull { attr(it, "DEFAULT") == "YES" } ?: media.firstOrNull()
                        audioUrl = pick?.let { absUrl(masterUrl, attr(it, "URI")!!) }
                    }
                    plUrl = absUrl(masterUrl, bestUrl ?: throw Exception("No video variants found"))
                    pl = text(plUrl, ref)
                }

                val tag = System.currentTimeMillis()
                val v = fetchMedia(plUrl, pl, ref, File(cacheDir, "dl_${tag}_v"), "V")
                var a: Fetched? = null
                if (audioUrl != null) {
                    a = fetchMedia(audioUrl, text(audioUrl, ref), ref, File(cacheDir, "dl_${tag}_a"), "A")
                }
                runOnUiThread { dl.text = "MP4..." }
                val tmpMp4 = File(cacheDir, "dl_${tag}.mp4")
                val converted = remuxToMp4(listOfNotNull(v.file, a?.file), tmpMp4)
                lastReport = "Playlist: ${URL(plUrl).path}\n" +
                    "Separate audio playlist: ${if (audioUrl != null) "yes" else "no"}\n" +
                    v.stats + "\n" + (a?.stats?.plus("\n") ?: "") +
                    "MP4 convert: ${if (converted) "OK" else "FAILED"}\n" + remuxInfo
                val src = if (converted) tmpMp4 else v.file
                val ext = if (converted || v.fmp4) ".mp4" else ".ts"
                val name = (base ?: "video_$tag") + ext
                val (fo, saveDone) = openOut(name, if (ext == ".mp4") "video/mp4" else "video/mp2t")
                src.inputStream().use { i -> fo.use { i.copyTo(it) } }
                saveDone()
                cacheDir.listFiles()?.filter { it.name.startsWith("dl_") }?.forEach { it.delete() }
                if (!converted) toast(if (a != null) "MP4 merge failed: saved video only (no audio)." else "MP4 convert failed: saved raw file.")
                addSaved(v.secs / 60.0 * RATE_PER_MIN)
                ok = true
                toast("Saved to Downloads: $name (long-press Save for a report)")
            } catch (e: Exception) {
                cacheDir.listFiles()?.filter { it.name.startsWith("dl_") }?.forEach { it.delete() }
                toast("HLS download failed: ${e.message}")
            } finally {
                busy = false
                runOnUiThread { dl.text = "Save"; onDone?.invoke(ok) }
            }
        }.start()
    }

    // Re-wraps one or more media files (e.g. video + separate audio) into one MP4 without re-encoding.
    private fun remuxToMp4(srcs: List<File>, dst: File): Boolean {
        val exs = ArrayList<MediaExtractor>()
        var mux: MediaMuxer? = null
        var started = false
        var good = false
        var hasVideo = false
        var hasAudio = false
        var vN = 0
        var aN = 0
        var lastUs = 0L
        val audioKeys = HashSet<Int>()
        remuxInfo = ""
        try {
            mux = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val maps = ArrayList<HashMap<Int, Int>>()
            for (f in srcs) {
                val ex = MediaExtractor()
                exs.add(ex)
                ex.setDataSource(f.absolutePath)
                val map = HashMap<Int, Int>()
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    remuxInfo += "src${exs.size - 1}:$mime "
                    if (mime.startsWith("video/")) hasVideo = true
                    if (mime.startsWith("audio/")) { hasAudio = true; audioKeys.add((exs.size - 1) * 1000 + i) }
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        ex.selectTrack(i)
                        map[i] = mux.addTrack(fmt)
                    }
                }
                maps.add(map)
            }
            if (!hasVideo) throw Exception("no video track")
            val base = LongArray(exs.size) { exs[it].sampleTime.coerceAtLeast(0L) }  // start every file at 0
            mux.start()
            started = true
            val buf = java.nio.ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                var pick = -1
                var best = Long.MAX_VALUE
                for (k in exs.indices) {
                    val t = exs[k].sampleTime
                    if (t >= 0 && t - base[k] < best) { best = t - base[k]; pick = k }
                }
                if (pick < 0) break
                val ex = exs[pick]
                val n = ex.readSampleData(buf, 0)
                val ti = maps[pick][ex.sampleTrackIndex]
                if (n >= 0 && ti != null) {
                    val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, n, (ex.sampleTime - base[pick]).coerceAtLeast(0L), flags)
                    mux.writeSampleData(ti, buf, info)
                    if (audioKeys.contains(pick * 1000 + ex.sampleTrackIndex)) aN++ else vN++
                    if (info.presentationTimeUs > lastUs) lastUs = info.presentationTimeUs
                }
                ex.advance()
            }
            good = true
        } catch (e: Exception) {
            good = false
            remuxInfo += "error: ${e.javaClass.simpleName} ${e.message} "
        }
        try { if (started) mux?.stop() } catch (e: Exception) { good = false }
        try { mux?.release() } catch (e: Exception) {}
        for (e in exs) try { e.release() } catch (x: Exception) {}
        remuxInfo += "wrote video=$vN audio=$aN lastPts=${lastUs / 1000000}s"
        if (good) {
            val chk = MediaExtractor()
            try {
                chk.setDataSource(dst.absolutePath)
                good = chk.trackCount >= (if (hasAudio) 2 else 1)
            } catch (e: Exception) { good = false } finally { chk.release() }
        }
        return good && dst.length() > 0
    }

    // ---------- Playlist / series downloader ----------

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayList<Pair<String, String>>()
    private var queueActive = false
    private var queueTotal = 0
    private var queueIdx = 0
    private var queueOk = 0
    private var episodeTitle = ""
    private var waitStart = 0L

    private val linksJs = "(function(){var o=[],seen={};document.querySelectorAll('a[href]').forEach(function(a){" +
        "var h=a.href;if(!/^https?:/.test(h)||seen[h])return;seen[h]=1;" +
        "var t=(a.innerText||a.title||'').trim().replace(/\\s+/g,' ').slice(0,60);o.push([t,h]);});return JSON.stringify(o);})()"

    private val playJs = "(function(){try{document.querySelectorAll('video').forEach(function(v){" +
        "var p=v.play();if(p&&p.catch)p.catch(function(){});});" +
        "['.vjs-big-play-button','.jw-icon-display','.plyr__control--overlaid','.play-button','.fp-play','[aria-label=Play]']" +
        ".forEach(function(q){var e=document.querySelector(q);if(e)e.click();});}catch(e){}})();"

    private fun paintSeries() {
        if (queueActive) { seriesBtn.text = "Stop"; paint(seriesBtn, 0xFFFFC46B.toInt(), 0xFFE67E1F.toInt()) }
        else { seriesBtn.text = "Series"; paint(seriesBtn, 0xFF7AE8D8.toInt(), 0xFF1AA58F.toInt()) }
    }

    private fun safeName(t: String) = t.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().take(60).ifBlank { "episode" }

    private fun scanEpisodes() {
        val cur = web.url
        if (cur == null) { toast("Open a series page with an episode list first."); return }
        web.evaluateJavascript(linksJs) { raw ->
            try {
                val inner = JSONTokener(raw).nextValue() as String
                val arr = JSONArray(inner)
                val curSite = siteOf(Uri.parse(cur).host)
                val curClean = cur.substringBefore('#')
                val skip = Regex("(?i)/(login|register|signup|privacy|terms|contact|dmca|about|faq|search|tag|genre|category)(/|\\?|$)|\\.(jpg|png|gif|css|js)(\\?|$)")
                val like = Regex("(?i)(ep|episode|season|watch|play|\\d)")
                val seen = HashSet<String>()
                val items = ArrayList<Pair<String, String>>()
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONArray(i)
                    val t = e.getString(0)
                    val u = e.getString(1).substringBefore('#')
                    if (siteOf(Uri.parse(u).host) != curSite || u == curClean || !seen.add(u)) continue
                    if (skip.containsMatchIn(u) || !(like.containsMatchIn(u) || like.containsMatchIn(t))) continue
                    items.add(Pair(t.ifBlank { Uri.parse(u).lastPathSegment ?: u }, u))
                }
                if (items.isEmpty()) toast("No episode links found on this page.")
                else showPicker(items.take(200))
            } catch (e: Exception) {
                toast("Couldn't read links: ${e.message}")
            }
        }
    }

    private fun showPicker(items: List<Pair<String, String>>) {
        val labels = items.mapIndexed { i, p -> "${i + 1}. ${p.first}" }.toTypedArray()
        val checked = BooleanArray(items.size) { true }
        AlertDialog.Builder(this)
            .setTitle("Download which? (${items.size} links found)")
            .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Download") { _, _ ->
                val chosen = items.filterIndexed { i, _ -> checked[i] }
                if (chosen.isEmpty()) toast("Nothing selected.") else startQueue(chosen)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startQueue(items: List<Pair<String, String>>) {
        if (!ensureStorage()) return
        queue.clear()
        queue.addAll(items)
        queueTotal = items.size
        queueIdx = 0
        queueOk = 0
        queueActive = true
        paintSeries()
        nextInQueue()
    }

    private fun stopQueue() {
        queueActive = false
        queue.clear()
        handler.removeCallbacksAndMessages(null)
        paintSeries()
        toast("Playlist stopped.")
    }

    private fun nextInQueue() {
        if (!queueActive) return
        if (queue.isEmpty()) {
            queueActive = false
            paintSeries()
            toast("Playlist finished: $queueOk of $queueTotal downloaded.")
            return
        }
        val (t, u) = queue.removeAt(0)
        queueIdx++
        episodeTitle = String.format(java.util.Locale.US, "%02d %s", queueIdx, t)
        toast("Episode $queueIdx of $queueTotal: $t")
        load(u)
        waitStart = System.currentTimeMillis()
        handler.postDelayed({ pollVideo() }, 1500)
    }

    private fun tapPlayer() {
        val x = web.width / 2f
        val y = web.height * 0.4f
        val t = android.os.SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        web.dispatchTouchEvent(d)
        web.dispatchTouchEvent(u)
        d.recycle(); u.recycle()
    }

    private fun pollVideo() {
        if (!queueActive) return
        val s = sniffed
        if (s != null) { grab(s); return }
        val sec = (System.currentTimeMillis() - waitStart) / 1000
        if (sec > 40) {
            toast("No video found for $episodeTitle, skipping.")
            nextInQueue()
            return
        }
        if (sec >= 2 && sec % 4 == 0L) web.evaluateJavascript(playJs, null)
        if (sec == 7L || sec == 18L || sec == 29L) tapPlayer()
        handler.postDelayed({ pollVideo() }, 1000)
    }

    private fun grab(s: String) {
        val base = safeName(episodeTitle)
        val low = s.lowercase()
        when {
            low.contains(".m3u8") -> downloadHls(s, web.url, base) { ok ->
                if (ok) queueOk++
                nextInQueue()
            }
            low.contains(".mpd") -> { toast("DASH not supported, skipping."); nextInQueue() }
            else -> {
                startDownload(s, null, base)
                queueOk++
                handler.postDelayed({ nextInQueue() }, 1500)
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(dlDone) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) { web.webChromeClient?.onHideCustomView(); return true }
            if (web.canGoBack()) { web.goBack(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
