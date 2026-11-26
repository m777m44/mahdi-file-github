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
    ) {
        override fun toString(): String = name
    }

    val ALL: List<Host> = listOf(
        Host("catbox", "Catbox", "catbox.moe", "دائم — رابط مباشر", true, true, false, 200),
        Host("litterbox", "Litterbox (مؤقت)", "litter.catbox.moe", "مؤقت — 72 ساعة", false, true, false, 150),
        Host("x0", "0x0.st", "0x0.st", "دائم تقريباً — كبير", true, true, false, 512),
        Host("pomf", "Pomf", "pomf.lain.la", "دائم — رابط مباشر", true, true, false, 1000),
        Host("gofile", "Gofile", "gofile.io", "دائم — صفحة تحميل", true, false, false, 0),
        Host("uguu", "Uguu", "uguu.se", "مؤقت — 3 ساعات", false, true, false, 128),
        Host("tmpfiles", "tmpfiles.org", "tmpfiles.org", "مؤقت — 60 دقيقة", false, true, false, 100),
        Host("pixhost", "PixHost — صور", "pixhost.to", "دائم — صور فقط", true, true, true, 10),
    )

    fun byId(id: String): Host = ALL.find { it.id == id } ?: ALL[0]

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
