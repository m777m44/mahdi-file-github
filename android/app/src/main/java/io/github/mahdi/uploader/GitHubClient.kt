package io.github.mahdi.uploader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class GitHubClient(private val token: String) {

    class GitHubException(
        val code: Int,
        message: String,
        val retryAfterSeconds: Long? = null,
    ) : Exception(message)

    companion object {
        private const val PACE_MS = 90L
        private val RATE_LIMIT_WAITS_MS =
            longArrayOf(60_000L, 90_000L, 150_000L, 240_000L, 420_000L, 600_000L)
        private val NETWORK_BACKOFF_MS = longArrayOf(5_000L, 15_000L, 45_000L)
        // 1.9.4: إعادة محاولة أخطاء خادم GitHub المؤقتة (5xx) قبل إعلان الفشل
        private val SERVER_BACKOFF_MS = longArrayOf(2_000L, 5_000L, 10_000L)

        // عميل HTTP مشترك واحد لكل التطبيق (مجمّع اتصالات موحّد = إعادة استخدام كامل + سرعة أعلى)
        val SHARED_HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()
    }

    private val http = SHARED_HTTP

    private val paceLock = Any()
    private var lastCallAt = 0L

    private fun pace() {
        synchronized(paceLock) {
            val now = System.currentTimeMillis()
            val next = lastCallAt + PACE_MS
            if (next > now) {
                try {
                    Thread.sleep(next - now)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                lastCallAt = System.currentTimeMillis()
            } else {
                lastCallAt = now
            }
        }
    }

    private fun isRateLimitResponse(msg: String): Boolean =
        msg.contains("rate limit", ignoreCase = true) ||
            msg.contains("abuse", ignoreCase = true) ||
            msg.contains("secondary", ignoreCase = true)

    private fun call(
        method: String,
        url: String,
        jsonBody: String? = null,
        useAuth: Boolean = true,
        rawBody: RequestBody? = null,
    ): Pair<Int, String> {
        var attempt = 0
        var netAttempt = 0
        var serverAttempt = 0
        while (true) {
            pace()
            val builder = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
            if (useAuth) builder.header("Authorization", "Bearer $token")
            when {
                rawBody != null -> builder.method(method, rawBody)
                jsonBody != null -> builder.method(
                    method,
                    jsonBody.toRequestBody("application/json".toMediaType())
                )
                method != "GET" && method != "HEAD" -> builder.method(method, null)
            }

            val resp = try {
                http.newCall(builder.build()).execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                if (netAttempt < NETWORK_BACKOFF_MS.size) {
                    Thread.sleep(NETWORK_BACKOFF_MS[netAttempt])
                    netAttempt++
                    continue
                }
                throw GitHubException(-1, "تعذر الاتصال بـ GitHub — تحقّق من الاتصال بالإنترنت ثم أعد المحاولة (${e.message ?: "شبكة"})")
            }
            val code = resp.code
            val retryAfter = resp.header("Retry-After")?.toLongOrNull()
            val bodyStr = resp.body?.string().orEmpty()
            resp.close()

            if (!resp.isSuccessful) {
                val msg = try {
                    JSONObject(bodyStr).optString("message")
                } catch (_: Exception) {
                    bodyStr.take(200).ifEmpty { resp.message }
                }
                if (isRateLimitResponse(msg) && (code == 429 || code == 403) &&
                    attempt < RATE_LIMIT_WAITS_MS.size
                ) {
                    val waitSeconds = retryAfter?.takeIf { it > 0 }
                    val waitMs = waitSeconds?.times(1000) ?: RATE_LIMIT_WAITS_MS[attempt]
                    Thread.sleep(waitMs)
                    attempt++
                    continue
                }
                // 1.9.4: خطأ خادم مؤقت (500/502/503/504) — إعادة محاولة تلقائية 3 مرات
                if (code in listOf(500, 502, 503, 504) && serverAttempt < SERVER_BACKOFF_MS.size) {
                    Thread.sleep(SERVER_BACKOFF_MS[serverAttempt])
                    serverAttempt++
                    continue
                }
                if (code in listOf(500, 502, 503, 504)) {
                    throw GitHubException(code, "خطأ مؤقت في خادم GitHub — جرّب بعد قليل", retryAfter)
                }
                throw GitHubException(code, "GitHub ($code): $msg", retryAfter)
            }
            return Pair(code, bodyStr)
        }
    }

    private fun api(path: String) = "https://api.github.com$path"

    private fun json(method: String, path: String, body: JSONObject? = null): JSONObject {
        val (_, text) = call(method, api(path), body?.toString())
        return JSONObject(text.ifEmpty { "{}" })
    }

    // ------------------------------------------------------------ auth

    fun requestDeviceCode(clientId: String): JSONObject {
        val body = JSONObject().put("client_id", clientId)
        val (_, text) = call(
            "POST", "https://github.com/login/device/code", body.toString(), useAuth = false,
        )
        return JSONObject(text)
    }

    fun pollForToken(clientId: String, deviceCode: String): JSONObject {
        val body = JSONObject()
            .put("client_id", clientId)
            .put("device_code", deviceCode)
            .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
        val (_, text) = call(
            "POST", "https://github.com/login/oauth/access_token", body.toString(), useAuth = false,
        )
        return JSONObject(text)
    }

    fun getUser(): JSONObject {
        val (_, text) = call("GET", api("/user"))
        return JSONObject(text)
    }

    // ------------------------------------------------------------ repos

    suspend fun listRepos(): JSONArray = withContext(Dispatchers.IO) {
        val all = JSONArray()
        var page = 1
        while (true) {
            val (_, text) = call(
                "GET",
                api("/user/repos?affiliation=owner,collaborator,organization_member&sort=updated&per_page=100&page=$page"),
            )
            val batch = JSONArray(text)
            for (i in 0 until batch.length()) all.put(batch.getJSONObject(i))
            if (batch.length() < 100) break
            page++
        }
        all
    }

    fun createRepo(name: String, private: Boolean, description: String): JSONObject {
        val body = JSONObject()
            .put("name", name)
            .put("private", private)
            .put("description", description)
            .put("auto_init", true)
        return json("POST", "/user/repos", body)
    }

    fun getRepo(fullName: String): JSONObject = json("GET", "/repos/$fullName")

    fun latestRelease(fullName: String): JSONObject? {
        return try {
            val (_, text) = call("GET", api("/repos/$fullName/releases/latest"), useAuth = false)
            JSONObject(text)
        } catch (e: GitHubException) {
            if (e.code == 404) null else throw e
        }
    }

    fun listRepoDirectory(fullName: String, dir: String, ref: String?): JSONArray? {
        val q = if (ref.isNullOrEmpty()) "" else "?ref=" + java.net.URLEncoder.encode(ref, "UTF-8")
        return try {
            val (_, text) = call("GET", api("/repos/$fullName/contents/$dir$q"), useAuth = false)
            JSONArray(text)
        } catch (e: GitHubException) {
            if (e.code == 404) null else throw e
        }
    }

    fun getRef(fullName: String, branch: String): JSONObject =
        json("GET", "/repos/$fullName/git/ref/heads/$branch")

    fun getCommit(fullName: String, sha: String): JSONObject =
        json("GET", "/repos/$fullName/git/commits/$sha")

    fun createBlob(fullName: String, file: File): JSONObject {
        val body: RequestBody = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("{\"content\":\"")
                val encoder = java.util.Base64.getEncoder()
                file.inputStream().buffered().use { input ->
                    val buf = ByteArray(3 * 16384)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        val chunk = if (n == buf.size) buf else java.util.Arrays.copyOf(buf, n)
                        sink.writeUtf8(encoder.encodeToString(chunk))
                    }
                }
                sink.writeUtf8("\",\"encoding\":\"base64\"}")
            }
        }
        val (_, text) = call("POST", api("/repos/$fullName/git/blobs"), rawBody = body)
        return JSONObject(text)
    }

    fun createTree(fullName: String, baseTree: String, tree: JSONArray): JSONObject =
        json(
            "POST", "/repos/$fullName/git/trees",
            JSONObject().put("base_tree", baseTree).put("tree", tree),
        )

    fun createCommit(fullName: String, message: String, treeSha: String, parentShas: List<String>): JSONObject =
        json(
            "POST", "/repos/$fullName/git/commits",
            JSONObject().put("message", message).put("tree", treeSha)
                .put("parents", JSONArray(parentShas)),
        )

    fun updateRef(fullName: String, branch: String, sha: String) {
        json(
            "PATCH", "/repos/$fullName/git/refs/heads/$branch",
            JSONObject().put("sha", sha).put("force", false),
        )
    }

    fun createRef(fullName: String, branch: String, sha: String) {
        json(
            "POST", "/repos/$fullName/git/refs",
            JSONObject().put("ref", "refs/heads/$branch").put("sha", sha),
        )
    }

    // ------------------------------------------------------------ إدارة المستودع (1.10)

    private fun encPath(path: String): String =
        path.split("/").joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }

    /** شجرة المستودع كاملة (مجلدات وملفات) على الفرع المحدد */
    fun listTree(fullName: String, branch: String): JSONArray {
        val (_, text) = call(
            "GET", api("/repos/$fullName/git/trees/$branch?recursive=1"),
        )
        return JSONObject(text).optJSONArray("tree") ?: JSONArray()
    }

    fun getContentsMeta(fullName: String, path: String, ref: String): JSONObject {
        val (_, text) = call(
            "GET", api("/repos/$fullName/contents/${encPath(path)}?ref=" +
                java.net.URLEncoder.encode(ref, "UTF-8")),
        )
        return JSONObject(text)
    }

    /** حذف ملف واحد عبر Contents API */
    fun deleteFile(fullName: String, path: String, branch: String, sha: String, message: String) {
        json(
            "DELETE", "/repos/$fullName/contents/${encPath(path)}",
            JSONObject().put("sha", sha).put("branch", branch).put("message", message),
        )
    }

    /**
     * حذف مجموعة مسارات في التزام واحد (شجرة جديدة مع sha=null لكل مسار محذوف).
     * يعيد عدد المسارات المُحذوفة. أسرع بكثير من الحذف ملفاً ملفاً.
     */
    fun deletePathsViaTree(fullName: String, branch: String, paths: List<String>): Int {
        if (paths.isEmpty()) return 0
        val ref = getRef(fullName, branch)
        val headSha = ref.getJSONObject("object").getString("sha")
        val headCommit = getCommit(fullName, headSha)
        val baseTree = headCommit.getJSONObject("tree").getString("sha")
        val tree = JSONArray()
        for (p in paths) {
            tree.put(
                JSONObject().put("path", p).put("mode", "100644")
                    .put("type", "blob").put("sha", JSONObject.NULL),
            )
        }
        val newTree = createTree(fullName, baseTree, tree)
        val commit = createCommit(
            fullName, "حذف ${paths.size} مسار عبر تطبيق GitHub Uploader",
            newTree.getString("sha"), listOf(headSha),
        )
        updateRef(fullName, branch, commit.getString("sha"))
        return paths.size
    }

    /** حذف المسارات مع الرجوع إلى الحذف ملفاً ملفاً عند فشل طريقة الشجرة */
    fun deletePathsRobust(fullName: String, branch: String, paths: List<String>): Int {
        try {
            return deletePathsViaTree(fullName, branch, paths)
        } catch (e: GitHubException) {
            if (e.code !in listOf(422, 500, 502, 503, 504)) throw e
        }
        // خطة بديلة: حذف ملفاً ملفاً (الحدة الأبطأ لكن أضمن)
        var deleted = 0
        for (p in paths) {
            try {
                val meta = getContentsMeta(fullName, p, branch)
                deleteFile(fullName, p, branch, meta.getString("sha"),
                    "حذف ${p.substringAfterLast('/')} عبر التطبيق")
                deleted++
            } catch (e: GitHubException) {
                if (e.code !in listOf(404, 409)) throw e
            }
        }
        return deleted
    }

    /** حذف المستودع بالكامل — يتطلب توكن بصلاحية delete_repo */
    fun deleteRepo(fullName: String) {
        try {
            call("DELETE", api("/repos/$fullName"))
        } catch (e: GitHubException) {
            if (e.code == 403) {
                throw GitHubException(403,
                    "التوكن الحالي لا يملك صلاحية حذف المستودعات (delete_repo) — " +
                        "أنشئ Personal Access Token جديد من github.com/settings/tokens وفعّل صلاحية delete_repo",
                    e.retryAfterSeconds)
            }
            throw e
        }
    }

    fun putFile(fullName: String, path: String, content: String, message: String, branch: String): JSONObject {
        val b64 = android.util.Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP,
        )
        return json(
            "PUT", "/repos/$fullName/contents/$path",
            JSONObject().put("message", message).put("content", b64).put("branch", branch),
        )
    }
}
