package io.github.mahdi.uploader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File

/**
 * ⚡ مركز الرفع — واجهة كاملة بأسلوب raf3ly (مطوَّرة من مستودع shere-file-heroed):
 * رفع برابط مباشر إلى 14 منصة + اختيار ذكي تلقائي حسب نوع الملف (صورة/فيديو/صوت/أرشيف)
 * + سلسلة بدائل تلقائية + كاميرا + سجل الروابط + فلاتر منصات.
 */
class ShareCenterActivity : AppCompatActivity() {

    private class Item(val file: File, val name: String, val mime: String)

    private val files = mutableListOf<Item>()
    private var chosenHost: ShareHosts.Host = ShareHosts.ALL[0]
    private var autoPick = true
    private var uploadJobActive = false
    private var filterMode = 0 // 0=الكل 1=دائم 2=مؤقت 3=سريع

    private lateinit var queueSummary: TextView
    private lateinit var chosenHostView: TextView
    private lateinit var startBtn: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var status: TextView
    private lateinit var resultsBox: LinearLayout
    private lateinit var swAuto: SwitchMaterial

    /** 🛡️ فرض العربية بأسلوب آمن موحّد (نفس نمط MainActivity) — قبل إنشاء أي مورد */
    override fun attachBaseContext(newBase: Context) {
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(java.util.Locale("ar"))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    private val pickAny = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        handlePicked(uris)
    }
    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        handlePicked(uris)
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            val f = cameraOut ?: return@registerForActivityResult
            cameraOut = null
            files.add(Item(f, f.name, "image/jpeg"))
            refreshQueue()
        }
    }
    private var cameraOut: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 🛡️ غلاف دفاعي: أي فشل في تهيئة الواجهة لا يُنهار التطبيق — يُغلق بهدوء مع رسالة
        try {
            initUi(savedInstanceState)
        } catch (e: Throwable) {
            try {
                Toast.makeText(this, "⚠️ تعذر فتح شاشة المشاركة", Toast.LENGTH_LONG).show()
            } catch (_: Throwable) { }
            finish()
        }
    }

    private fun initUi(@Suppress("UNUSED_PARAMETER") savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_share_center)

        queueSummary = findViewById(R.id.tvQueueSummary)
        chosenHostView = findViewById(R.id.tvChosenHost)
        startBtn = findViewById(R.id.btnStartUpload)
        progress = findViewById(R.id.centerProgress)
        status = findViewById(R.id.tvCenterStatus)
        resultsBox = findViewById(R.id.centerResults)
        swAuto = findViewById(R.id.swAutoPick)

        // زر التبديل إلى واجهة رفع GitHub 🔼
        findViewById<MaterialButton>(R.id.btnSwitchGithub).setOnClickListener {
            if (uploadJobActive) {
                Toast.makeText(this, getString(R.string.sc_busy), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ShareBridge.pendingGithubUpload = true
            finish()
        }
        findViewById<ImageButton>(R.id.btnCloseCenter).setOnClickListener { finish() }

        swAuto.setOnCheckedChangeListener { _, checked ->
            autoPick = checked
            refreshHostLabel()
        }

        findViewById<MaterialButton>(R.id.btnPickAny).setOnClickListener {
            hapticCompat(this); pickAny.launch(arrayOf("*/*"))
        }
        findViewById<MaterialButton>(R.id.btnPickImages).setOnClickListener {
            hapticCompat(this); pickImages.launch("image/*")
        }
        findViewById<MaterialButton>(R.id.btnCamera).setOnClickListener {
            hapticCompat(this)
            try {
                val dir = File(cacheDir, "camera").apply { mkdirs() }
                val f = File(dir, "cam_${System.currentTimeMillis()}.jpg")
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", f)
                cameraOut = f
                camera.launch(uri)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.sc_camera_failed), Toast.LENGTH_SHORT).show()
            }
        }

        startBtn.setOnClickListener { startUpload() }
        setupFilters()
        findViewById<MaterialButton>(R.id.btnClearHistory).setOnClickListener {
            hapticCompat(this)
            ShareHistory.clear(this)
            resultsBox.removeAllViews()
            resultsBox.isVisible = false
            Toast.makeText(this, getString(R.string.sc_history_cleared), Toast.LENGTH_SHORT).show()
        }
        try {
            buildPlatformList()
            refreshHostLabel()
            renderHistory()
        } catch (_: Throwable) { }
    }

    /** 🎛️ فلاتر المنصات: الكل / دائم / مؤقت / سريع */
    private fun setupFilters() {
        val ids = intArrayOf(R.id.btnFilterAll, R.id.btnFilterPermanent,
            R.id.btnFilterTemp, R.id.btnFilterFast)
        for ((i, id) in ids.withIndex()) {
            val btn = findViewById<MaterialButton>(id)
            btn.setOnClickListener {
                hapticCompat(this)
                filterMode = i
                for ((j, other) in ids.withIndex()) {
                    val on = j == i
                    findViewById<MaterialButton>(other).alpha = if (on) 1f else 0.45f
                }
                try { buildPlatformList() } catch (_: Throwable) { }
            }
            btn.alpha = if (i == 0) 1f else 0.45f
        }
    }

    private fun handlePicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val staged = mutableListOf<Item>()
        Thread {
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
                val name = queryName(uri) ?: "file_${System.currentTimeMillis()}"
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                try {
                    val dir = File(cacheDir, "center_stage").apply { mkdirs() }
                    val f = File(dir, "${System.currentTimeMillis()}_$name")
                    contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    } ?: continue
                    staged.add(Item(f, name, mime))
                } catch (_: Exception) { }
            }
            runOnUiThread {
                files.addAll(staged)
                refreshQueue()
            }
        }.start()
        Toast.makeText(this, getString(R.string.sc_staging, uris.size), Toast.LENGTH_SHORT).show()
    }

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }

    private fun refreshQueue() {
        startBtn.isEnabled = files.isNotEmpty() && !uploadJobActive
        if (files.isEmpty()) {
            queueSummary.text = getString(R.string.sc_queue_empty)
            return
        }
        val total = files.sumOf { it.file.length() }
        val kinds = files.groupBy { ShareHosts.kindOf(it.name, it.mime) }
            .entries.joinToString(" ، ") { (k, v) ->
                "${ShareHosts.kindLabel(k)} ×${v.size}"
            }
        queueSummary.text = getString(
            R.string.sc_queue_summary, files.size,
            Formatter.formatShortFileSize(this, total)) + "\n🤖 $kinds"
    }

    private fun refreshHostLabel() {
        if (autoPick) {
            chosenHostView.text = getString(R.string.sc_auto_host)
        } else {
            chosenHostView.text = "📍 ${chosenHost.name} — ${chosenHost.domain} • ${chosenHost.life}"
        }
    }

    private fun buildPlatformList() {
        val box = findViewById<LinearLayout>(R.id.platformsBox)
        box.removeAllViews()
        val hosts = ShareHosts.ALL.filter { h ->
            when (filterMode) {
                1 -> h.permanent
                2 -> !h.permanent
                3 -> h.fast
                else -> true
            }
        }
        for ((idx, host) in hosts.withIndex()) {
            val card = LayoutInflater.from(this).inflate(R.layout.item_host_card, box, false)
            card.findViewById<TextView>(R.id.hostEmoji).text =
                if (host.imageOnly) "🖼️" else "☁️"
            card.findViewById<TextView>(R.id.hostName).text = host.name
            card.findViewById<TextView>(R.id.hostVerified).isVisible = host.verified
            card.findViewById<TextView>(R.id.hostNote).text = host.note
            val tags = buildString {
                append("⏳ ${host.life}")
                if (host.direct) append("  •  ⚡ رابط مباشر")
                if (host.maxSizeMB > 0) append("  •  الحد ${host.maxSizeMB}MB")
            }
            card.findViewById<TextView>(R.id.hostTags).text = tags
            card.setOnClickListener {
                if (uploadJobActive) return@setOnClickListener
                hapticCompat(this)
                autoPick = false
                swAuto.isChecked = false
                chosenHost = host
                refreshHostLabel()
                // إبراز البطاقة المختارة يدوياً بلون أخضر
                for (i in 0 until box.childCount) {
                    val mark = box.getChildAt(i).findViewById<TextView>(R.id.hostSelectMark)
                    mark.setTextColor(if (i == box.indexOfChild(card)) 0xFF3FB950.toInt() else 0xFF30363D.toInt())
                }
            }
            box.addView(card)
            // ✨ أنيميشن دخول متدرجة أنيقة لكل بطاقة
            card.alpha = 0f
            card.translationY = 42f
            card.animate().alpha(1f).translationY(0f).setDuration(230L)
                .setStartDelay((idx * 45L))
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
            // إبراز البطاقة المختارة سابقاً
            if (!autoPick && host.id == chosenHost.id) {
                val mark = card.findViewById<TextView>(R.id.hostSelectMark)
                mark.setTextColor(0xFF3FB950.toInt())
            }
        }
    }

    private fun startUpload() {
        if (files.isEmpty()) {
            Toast.makeText(this, getString(R.string.sc_pick_first), Toast.LENGTH_SHORT).show()
            return
        }
        if (uploadJobActive) return
        uploadJobActive = true
        startBtn.isEnabled = false
        resultsBox.removeAllViews()
        resultsBox.isVisible = true
        progress.isVisible = true
        progress.setProgressCompat(0, false)

        Thread {
            var ok = 0
            val total = files.size
            for ((pos, item) in files.withIndex()) {
                runOnUiThread {
                    status.isVisible = true
                    status.text = getString(R.string.sc_uploading, pos + 1, total, item.name)
                }
                try {
                    val primary = if (autoPick) {
                        ShareHosts.pickAuto(item.file, item.mime)
                    } else chosenHost
                    val result = ShareHosts.uploadWithFallback(
                        primary, item.file, item.name, item.mime,
                        { bytes, t ->
                            if (t > 0) runOnUiThread {
                                val pct = ((bytes * 100) / t).toInt().coerceIn(0, 100)
                                progress.setProgressCompat(pct, true)
                                status.text = getString(
                                    R.string.share_percent, pct,
                                    Formatter.formatShortFileSize(this, bytes),
                                    Formatter.formatShortFileSize(this, t))
                            }
                        },
                        { trying, idx ->
                            runOnUiThread {
                                if (idx > 1) status.text =
                                    getString(R.string.sc_fallback, trying.name, idx)
                            }
                        },
                    )
                    ok++
                    ShareHistory.add(this, item.name, result.url, result.hostName, result.life)
                    runOnUiThread { addResultCard(item.name, result) }
                } catch (e: Exception) {
                    runOnUiThread {
                        status.text = "⚠️ ${item.name}: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
            runOnUiThread {
                progress.isVisible = false
                status.text = getString(R.string.sc_all_done, ok, total)
                startBtn.isEnabled = true
                uploadJobActive = false
                hapticCompat(this, true)
                refreshQueue()
            }
        }.start()
    }

    private fun addResultCard(name: String, result: ShareHosts.SharedResult) {
        val card = LayoutInflater.from(this).inflate(R.layout.item_share_result, resultsBox, false)
        card.findViewById<TextView>(R.id.resultName).text = "$name — ${result.hostName} (${result.life})"
        card.findViewById<TextView>(R.id.resultUrl).text = result.url
        card.findViewById<MaterialButton>(R.id.btnCopyLink).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("url", result.url))
            Toast.makeText(this, getString(R.string.share_copied), Toast.LENGTH_SHORT).show()
            hapticCompat(this)
        }
        card.findViewById<MaterialButton>(R.id.btnSendLink).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND)
            send.type = "text/plain"
            send.putExtra(Intent.EXTRA_TEXT, "$name\n${result.url}")
            startActivity(Intent.createChooser(send, getString(R.string.share_btn_send)))
        }
        resultsBox.addView(card, 0)
    }

    private fun renderHistory() {
        val items = ShareHistory.load(this)
        if (items.isEmpty()) return
        resultsBox.isVisible = true
        // أحدث 5 روابط من السجل تُعرض أعلى النتائج
        for (it in items.take(5)) addResultCard(it.name,
            ShareHosts.SharedResult(it.url, it.host, it.life))
    }

    private fun hapticCompat(ctx: Context, long: Boolean = false) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                v.vibrate(android.os.VibrationEffect.createOneShot(
                    if (long) 40L else 15L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(if (long) 40L else 15L)
            }
        } catch (_: Throwable) { }
    }
}
