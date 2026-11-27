package io.github.mahdi.uploader

import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * محرّك المشاركة السريعة — منقول ومطوَّر من سكربت raf3ly (مستودع shere-file-heroed).
 * يرفع الملف إلى منصات المشاركة المجهولة ويعيد رابطاً مباشراً قابلاً للنسخ والمشاركة.
 */
object ShareHosts {

    class Host(
        val id: String,
        val name: String,
        val domain: String,
        val life: String,          // وصف مدة الاحتفاظ بالملف
        val permanent: Boolean,    // رابط دائم
        val direct: Boolean,       // الرابط يعرض الملف مباشرة
        val imageOnly: Boolean,    // الصور فقط
        val maxSizeMB: Int,        // 0 = بلا حد معلن
        val note: String = "",     // وصف عربي للمنصة
        val verified: Boolean = false, // مختبرة وتعمل ✓
        val fast: Boolean = false, // منصة سريعة خاصة بالصور والملفات الصغيرة
    ) {
        override fun toString(): String = name
    }

    val ALL: List<Host> = listOf(
        Host("catbox", "Catbox", "catbox.moe", "دائم — رابط مباشر", true, true, false, 200,
            "بدون حد زمني — الأفضل للاحتفاظ بالملفات للأبد.", verified = true),
        Host("x0", "0x0.st", "0x0.st", "30 يوم+ — كبير", true, true, false, 512,
            "الأكثر استقراراً — لينك مباشر يعمل في أي مكان.", verified = true),
        Host("pomf", "Pomf", "pomf.lain.la", "30 يوم — رابط مباشر", true, true, false, 1000,
            "سريع للصور والملفات الصغيرة.", verified = true, fast = true),
        Host("kappa", "Kappa", "kappa.lol", "30 يوم — رابط مباشر", true, true, false, 1000,
            "سريع جداً ورد فوري، لينك مباشر.", verified = true, fast = true),
        Host("0x0st", "0x0.st (بديل)", "0x0.st", "30 يوم+ — مباشر", true, true, false, 512,
            "من أقدم خدمات الرفع المجهولة — لينك مباشر.", fast = false),
        Host("fars", "Fars.ee", "fars.ee", "30 يوم — رابط مباشر", true, true, false, 512,
            "نسخة من عائلة 0x0 — لينك مباشر بدون إعلانات."),
        Host("gofile", "Gofile", "gofile.io", "10 أيام — صفحة تحميل", false, false, false, 0,
            "الأفضل للملفات الكبيرة جداً (فيديو/أرشيف) — بلا حد للحجم.", verified = true),
        Host("quax", "Qu.ax", "qu.ax", "7 أيام — مباشر", false, true, false, 2048,
            "سريع جداً، بيرجّع لينك مباشر.", verified = true, fast = true),
        Host("filebin", "Filebin", "filebin.net", "6 أيام — مجلد مشاركة", false, false, false, 0,
            "بينشئ مجلد (bin) بيتجمع فيه كل ملفاتك — رابط صفحة تحميل.", verified = true),
        Host("litterbox", "Litterbox (مؤقت)", "litter.catbox.moe", "مؤقت — 72 ساعة", false, true, false, 150,
            "نسخة مؤقتة من catbox — مثالي للمشاركة اللحظية."),
        Host("pixhost", "PixHost — صور", "pixhost.to", "دائم — صور فقط", true, true, true, 10,
            "استضافة صور (JPG/PNG/GIF) — لينك مباشر وصفحة عرض."),
        Host("pixeldrain", "Pixeldrain", "pixeldrain.com", "60 يوم — صفحة تحميل", false, false, false, 0,
            "مساحة كبيرة للملفات، بيرجّع صفحة تنزيل سريعة.", verified = true),
        Host("uguu", "Uguu", "uguu.se", "مؤقت — 3 ساعات", false, true, false, 128,
            "مؤقت — للمشاركة اللحظية.", verified = true, fast = true),
        Host("tmpfiles", "tmpfiles.org", "tmpfiles.org", "مؤقت — 60 دقيقة", false, true, false, 100,
            "مؤقت جداً — بيتم استخراج اللينك المباشر من صفحته.", verified = true),
    )

    fun byId(id: String): Host = ALL.find { it.id == id } ?: ALL[0]

    // ------------------------------------------------------------ أنواع الملفات والاختيار الذكي

    enum class FileKind { IMAGE, VIDEO, AUDIO, ARCHIVE, DOC, OTHER }

    /** يتعرف على نوع الملف من الامتداد أو MIME */
    fun kindOf(name: String, mime: String): FileKind {
        val m = mime.lowercase(); val n = name.lowercase()
        return when {
            m.startsWith("image/") || listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg").any { n.endsWith(it) } -> FileKind.IMAGE
            m.startsWith("video/") || listOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".3gp").any { n.endsWith(it) } -> FileKind.VIDEO
            m.startsWith("audio/") || listOf(".mp3", ".wav", ".ogg", ".flac", ".m4a", ".aac").any { n.endsWith(it) } -> FileKind.AUDIO
            m == "application/zip" || listOf(".zip", ".rar", ".7z", ".tar", ".gz", ".apk").any { n.endsWith(it) } -> FileKind.ARCHIVE
            m.startsWith("text/") || listOf(".txt", ".md", ".pdf", ".doc", ".docx", ".json", ".xml", ".csv").any { n.endsWith(it) } -> FileKind.DOC
            else -> FileKind.OTHER
        }
    }

    /** وصف عربي جميل لنوع الملف مع أيقونة */
    fun kindLabel(kind: FileKind): String = when (kind) {
        FileKind.IMAGE -> "🖼️ صورة"
        FileKind.VIDEO -> "🎬 فيديو"
        FileKind.AUDIO -> "🎵 صوت"
        FileKind.ARCHIVE -> "📦 أرشيف"
        FileKind.DOC -> "📄 مستند"
        FileKind.OTHER -> "📁 ملف"
    }

    /**
     * الاختيار الذكي 🤖 — يختار المنصة الأنسب تلقائياً حسب نوع الملف وحجمه:
     * صورة → Catbox (دائم ومباشر) ثم Kappa/Pomf السريعة.
     * فيديو كبير → Gofile (بلا حد للحجم)، صغير → Catbox.
     * أرشيف كبير → Gofile، متوسط → Catbox، صغير → 0x0.st.
     * صوت → Catbox، مستند → Catbox ثم Pomf.
     */
    fun pickAuto(file: File, mime: String): Host {
        val kind = kindOf(file.name, mime)
        val sizeMB = file.length() / (1024.0 * 1024.0)
        return when (kind) {
            FileKind.IMAGE -> when {
                sizeMB <= 10 -> ALL.first { it.id == "catbox" }
                sizeMB <= 200 -> ALL.first { it.id == "catbox" }
                else -> ALL.first { it.id == "gofile" }
            }
            FileKind.VIDEO -> when {
                sizeMB <= 190 -> ALL.first { it.id == "catbox" }
                else -> ALL.first { it.id == "gofile" }
            }
            FileKind.AUDIO -> ALL.first { it.id == "catbox" }
            FileKind.ARCHIVE -> when {
                sizeMB > 190 -> ALL.first { it.id == "gofile" }
                sizeMB <= 50 -> ALL.first { it.id == "x0" }
                else -> ALL.first { it.id == "catbox" }
            }
            FileKind.DOC -> when {
                sizeMB <= 100 -> ALL.first { it.id == "pomf" }
                else -> ALL.first { it.id == "gofile" }
            }
            FileKind.OTHER -> when {
                sizeMB > 190 -> ALL.first { it.id == "gofile" }
                sizeMB <= 200 -> ALL.first { it.id == "catbox" }
                else -> ALL.first { it.id == "x0" }
            }
        }
    }

    /** سلسلة بدائل ذكية: المنصة المختارة أولاً ثم بدائل مناسبة لنفس النوع عند الفشل */
    fun fallbackChain(primary: Host, file: File, mime: String): List<Host> {
        val kind = kindOf(file.name, mime)
        val sizeMB = file.length() / (1024.0 * 1024.0)
        val chain = mutableListOf(primary)
        fun add(id: String) { ALL.find { it.id == id }?.let { if (it !in chain) chain.add(it) } }
        when (kind) {
            FileKind.IMAGE -> { add("kappa"); add("pomf"); add("quax") }
            FileKind.VIDEO -> { add("gofile"); add("quax"); add("pixeldrain") }
            FileKind.AUDIO -> { add("x0"); add("kappa"); add("gofile") }
            FileKind.ARCHIVE -> { add("gofile"); add("x0"); add("pixeldrain") }
            FileKind.DOC -> { add("pomf"); add("x0"); add("gofile") }
            FileKind.OTHER -> { add("x0"); add("pomf"); add("gofile") }
        }
        if (sizeMB > 190) { add("gofile"); add("filebin"); add("pixeldrain") }
        return chain
    }

    private val http: OkHttpClient = GitHubClient.SHARED_HTTP
    private val ua = "Mozilla/5.0 (Linux; Android 14) GitHubUploader/1.10"

    /** نتيجة رفع: الرابط النهائي + اسم المنصة */
    class SharedResult(val url: String, val hostName: String, val life: String)

    /**
     * يرفع الملف إلى المنصة المحددة ويعيد الرابط المباشر.
     * onProgress(bytes, total) تُستدعى من خيط التنزيل — قدّم أرقام صحيحة.
     */
    fun upload(
        host: Host,
        file: File,
        fileName: String,
        mime: String,
        onProgress: (Long, Long) -> Unit,
    ): SharedResult = when (host.id) {
        "catbox" -> SharedResult(
            text(parseMultipart(
                "https://catbox.moe/user/api.php",
                mapOf("reqtype" to "fileupload"), "fileToUpload",
                file, fileName, mime, onProgress,
            ), "catbox"), host.name, host.life)

        "litterbox" -> SharedResult(
            text(parseMultipart(
                "https://litterbox.catbox.moe/resources/internals/api.php",
                mapOf("reqtype" to "fileupload", "time" to "72h"), "fileToUpload",
                file, fileName, mime, onProgress,
            ), "catbox"), host.name, host.life)

        "x0" -> SharedResult(
            text(parseMultipart(
                "https://0x0.st", emptyMap(), "file",
                file, fileName, mime, onProgress,
            ), null), host.name, host.life)

        "tmpfiles" -> {
            val body = postMultipart(
                "https://tmpfiles.org/api/v1/upload", emptyMap(), "file",
                file, fileName, mime, onProgress,
            )
            val u = JSONObject(body).optJSONObject("data")?.optString("url").orEmpty()
            if (!u.startsWith("http")) throw IOException(shortBody(body))
            // الرابط المباشر = إدراج /dl/
            SharedResult(u.replace("tmpfiles.org/", "tmpfiles.org/dl/"), host.name, host.life)
        }

        "pomf", "uguu" -> {
            val endpoint = if (host.id == "pomf") "https://pomf.lain.la/upload.php"
            else "https://uguu.se/upload?output=json"
            val body = postMultipart(endpoint, emptyMap(), "files[]", file, fileName, mime, onProgress)
            val files = JSONObject(body).optJSONArray("files")
            val u = files?.optJSONObject(0)?.optString("url").orEmpty()
            if (!u.startsWith("http")) throw IOException(shortBody(body))
            SharedResult(u, host.name, host.life)
        }

        "kappa" -> {
            val body = postMultipart(
                "https://kappa.lol/api/upload", emptyMap(), "file",
                file, fileName, mime, onProgress,
            )
            val o = JSONObject(body)
            val idv = o.optString("id")
            if (idv.isEmpty()) throw IOException(shortBody(body))
            SharedResult("https://kappa.lol/$idv", host.name, host.life)
        }

        "quax" -> {
            val body = postMultipart(
                "https://qu.ax/upload", emptyMap(), "file",
                file, fileName, mime, onProgress,
            )
            val files = JSONObject(body).optJSONArray("files")
            val u = files?.optJSONObject(0)?.optString("url")
                ?.takeIf { it.startsWith("http") }
                ?: files?.optJSONObject(0)?.optString("link")?.takeIf { it.startsWith("http") }
            if (u == null) throw IOException(shortBody(body))
            SharedResult(u, host.name, host.life)
        }

        "0x0st", "fars" -> SharedResult(
            text(postMultipart(
                if (host.id == "fars") "https://fars.ee" else "https://0x0.st",
                emptyMap(), "file", file, fileName, mime, onProgress,
            ), null), host.name, host.life)

        "filebin" -> {
            // filebin: PUT خام إلى مجلد (bin) ثم يعيد رابط المجلد — يجمع كل ملفاتك بمكان
            val bin = "mahdi" + (System.currentTimeMillis() / 1000)
            val safe = fileName.replace(" ", "_")
            val url = "https://filebin.net/$bin/" + java.net.URLEncoder.encode(safe, "UTF-8").replace("+", "%20")
            val req = Request.Builder().url(url)
                .header("User-Agent", ua)
                .put(CountingFileBody(file, mime, onProgress))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            }
            SharedResult("https://filebin.net/$bin", host.name, host.life)
        }

        "pixeldrain" -> {
            val body = postMultipart(
                "https://pixeldrain.com/api/file", emptyMap(), "file",
                file, fileName, mime, onProgress,
            )
            val id = JSONObject(body).optString("id")
            if (id.isEmpty()) throw IOException(shortBody(body))
            SharedResult("https://pixeldrain.com/u/$id", host.name, host.life)
        }

        "gofile" -> {
            var server = "store1"
            try {
                val srv = JSONObject(getText("https://api.gofile.io/servers"))
                val arr = srv.optJSONObject("data")?.optJSONArray("servers")
                    ?: srv.optJSONArray("servers")
                val name = arr?.optJSONObject(0)?.optString("name").orEmpty()
                if (name.isNotEmpty()) server = name
            } catch (_: Exception) { }
            val body = postMultipart(
                "https://$server.gofile.io/contents/uploadfile", emptyMap(), "file",
                file, fileName, mime, onProgress,
            )
            val d = JSONObject(body).optJSONObject("data")
            val page = d?.optString("downloadPage").orEmpty()
            if (!page.startsWith("http")) throw IOException(shortBody(body))
            SharedResult(page, host.name, host.life)
        }

        "pixhost" -> {
            val body = postMultipart(
                "https://api.pixhost.to/images",
                mapOf("content_type" to "0"), "img",
                file, fileName, mime, onProgress,
            ).trim()
            var url = body.split(" ").lastOrNull().orEmpty()
            if (url.contains("pixhost.to/show/")) {
                try {
                    val page = getText(url)
                    val m = Regex("https://img\\d*\\.pixhost\\.to/images/[^\"' )]+").find(page)
                    if (m != null) url = m.value
                } catch (_: Exception) { }
            }
            if (!url.startsWith("http")) throw IOException(shortBody(body))
            SharedResult(url, host.name, host.life)
        }

        else -> throw IOException("منصة غير معروفة")
    }

    /**
     * رفع بذكاء مع سلسلة بدائل تلقائية: يجرّب المنصة المختارة، وعند فشلها
     * ينتقل تلقائياً للمنصة الأنسب التالية حتى ينجح الرفع — مثل آلية raf3ly.
     */
    fun uploadWithFallback(
        primary: Host,
        file: File,
        fileName: String,
        mime: String,
        onProgress: (Long, Long) -> Unit,
        onTryingHost: ((Host, Int) -> Unit)? = null,
    ): SharedResult {
        var lastError: Exception? = null
        val chain = fallbackChain(primary, file, mime)
        for ((idx, host) in chain.withIndex()) {
            onTryingHost?.invoke(host, idx + 1)
            try {
                return upload(host, file, fileName, mime, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("فشل الرفع على كل المنصات")
    }

    // ------------------------------------------------------------ helpers

    private fun shortBody(body: String): String = body.take(120).trim().ifEmpty { "رد غير مفهوم من الخادم" }

    private fun text(body: String, prefixIgnore: String?): String {
        val t = body.trim()
        if (!t.startsWith("http")) throw IOException(shortBody(body))
        return t
    }

    private fun getText(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun postMultipart(
        endpoint: String,
        extra: Map<String, String>,
        field: String,
        file: File,
        fileName: String,
        mime: String,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        for ((k, v) in extra) builder.addFormDataPart(k, v)
        builder.addFormDataPart(field, fileName, CountingFileBody(file, mime, onProgress))
        val req = Request.Builder().url(endpoint)
            .header("User-Agent", ua)
            .post(builder.build()).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} — ${shortBody(body)}")
            return body
        }
    }

    /** مثل postMultipart لكن مع معالجة خاصة لبعض المنصات */
    private fun parseMultipart(
        endpoint: String,
        extra: Map<String, String>,
        field: String,
        file: File,
        fileName: String,
        mime: String,
        onProgress: (Long, Long) -> Unit,
    ): String = postMultipart(endpoint, extra, field, file, fileName, mime, onProgress)

    /** جسم ملف مع عدّاد تقدّم — يرسل البايتات المنقولة لنرى النسبة المئوية */
    private class CountingFileBody(
        private val file: File,
        mime: String,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        private val type = "$mime".toMediaType()
        private val main = Handler(Looper.getMainLooper())

        override fun contentType(): okhttp3.MediaType? = type
        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = file.length()
            var written = 0L
            var lastPost = 0L
            file.inputStream().buffered().use { input ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    sink.write(buf, 0, n)
                    written += n
                    val now = System.currentTimeMillis()
                    if (now - lastPost > 200 || written == total) {
                        lastPost = now
                        main.post { onProgress(written, total) }
                    }
                }
            }
        }
    }
}
