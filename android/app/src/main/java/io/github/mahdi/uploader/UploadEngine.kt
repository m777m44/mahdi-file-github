package io.github.mahdi.uploader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

object UploadEngine {

    const val HARD_LIMIT = 100L * 1024 * 1024      // GitHub hard limit per file
    private const val SAFE_LIMIT = 98L * 1024 * 1024   // نرفع حتى 98 ميغابايت (حد GitHub الفعلي 100)
    private const val BLOB_CONCURRENCY = 5
    private const val COMMIT_PAUSE_MS = 400L
    private const val RATE_LIMIT_COOLDOWN_MS = 60_000L
    private const val FINAL_RETRY_PASSES = 2
    private const val TRANSIENT_RETRIES = 2
    private const val TRANSIENT_RETRY_MS = 3_000L

    class Item(val path: String, val size: Long, val file: File)

    class Result(
        val uploaded: List<String>,
        val failed: List<Pair<String, String>>,
        val commits: Int,
    )

    fun cleanPath(raw: String): String =
        raw.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

    private val ignoredNames = setOf(".DS_Store", "Thumbs.db", "desktop.ini")

    fun isJunk(relativePath: String): Boolean {
        val parts = cleanPath(relativePath).split("/")
        return parts.any { it == ".git" || it == "__MACOSX" || it in ignoredNames }
    }

    fun stableName(relativePath: String): String =
        cleanPath(relativePath)
            .replace(Regex("[^A-Za-z0-9._\\-/ ]"), "_")
            .trim('_')
            .ifEmpty { "file" }

    private fun isRateLimitError(message: String): Boolean =
        message.contains("rate limit", ignoreCase = true) ||
            message.contains("abuse", ignoreCase = true) ||
            message.contains("secondary", ignoreCase = true)

    private fun shortError(raw: String): String = when {
        isRateLimitError(raw) -> "تم تجاوز الحد المؤقت للرفع في GitHub — حاول مرة أخرى بعد بضع دقائق"
        else -> raw.take(300)
    }

    private class BlobOutcome(
        val item: Item,
        val sha: String?,
        val error: String?,
    ) {
        val rateLimited: Boolean
            get() = sha == null && error != null && isRateLimitError(error)

        companion object {
            fun ok(item: Item, sha: String) = BlobOutcome(item, sha, null)
        }
    }

    private suspend fun createBlobSafe(
        api: GitHubClient,
        repoFullName: String,
        item: Item,
    ): BlobOutcome {
        var attempt = 0
        while (true) {
            try {
                val blob = api.createBlob(repoFullName, item.file)
                return BlobOutcome.ok(item, blob.getString("sha"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                if (isRateLimitError(msg)) {
                    return BlobOutcome(item, null, msg)
                }
                if (attempt >= TRANSIENT_RETRIES) {
                    return BlobOutcome(item, null, msg)
                }
                attempt++
                delay(TRANSIENT_RETRY_MS)
            }
        }
    }

    private suspend fun createBlobs(
        api: GitHubClient,
        repoFullName: String,
        batch: List<Item>,
    ): List<BlobOutcome> = coroutineScope {
        val semaphore = Semaphore(BLOB_CONCURRENCY)
        batch.map { item ->
            async {
                semaphore.acquire()
                try {
                    createBlobSafe(api, repoFullName, item)
                } finally {
                    semaphore.release()
                }
            }
        }.awaitAll()
    }

    suspend fun extractZipFile(zipFile: File, zipName: String, workDir: File): List<Item> =
        withContext(Dispatchers.IO) {
            val base = cleanPath(zipName)
                .removeSuffix(".zip").removeSuffix(".ZIP").removeSuffix(".Zip")
                .ifEmpty { "archive" }
            val items = mutableListOf<Item>()
            val extractRoot = File(workDir, "extract").canonicalFile
            extractRoot.mkdirs()
            ZipFile(zipFile).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val rel = cleanPath(entry.name)
                    if (rel.isEmpty() || isJunk(rel)) continue
                    val resolved = File(extractRoot, rel).canonicalFile
                    if (!resolved.path.startsWith(extractRoot.path)) continue
                    resolved.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input ->
                        resolved.outputStream().use { output -> input.copyTo(output) }
                    }
                    val target = "$base/$rel"
                    val f = resolved
                    items.add(Item(target, f.length(), f))
                }
            }
            if (items.isEmpty()) throw Exception("ملف الـ zip فارغ أو كل محتوياته مستثناة")
            items
        }

    suspend fun upload(
        api: GitHubClient,
        repoFullName: String,
        branch: String,
        inputItems: List<Item>,
        message: String,
        onProgress: (done: Int, total: Int, current: String, bytesDone: Long) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val big = inputItems.filter { it.size > SAFE_LIMIT }

        val commitMessage = message.ifBlank { "رفع ملفات عبر تطبيق GitHub Uploader" }
        var headSha: String
        var treeSha: String

        val initial = try {
            val ref = api.getRef(repoFullName, branch)
            api.getCommit(repoFullName, ref.getJSONObject("object").getString("sha"))
        } catch (e: GitHubClient.GitHubException) {
            if (e.code !in listOf(404, 409)) throw e
            val readme = api.putFile(
                repoFullName, "README.md",
                "# ${repoFullName.substringAfter('/')}\n\nتمت تهيئة هذا المستودع عبر تطبيق GitHub Uploader.",
                "التهيئة الأولى للمستودع", branch,
            )
            readme.getJSONObject("commit")
        }
        headSha = initial.getString("sha")
        treeSha = initial.getJSONObject("tree").getString("sha")

        val uploaded = mutableListOf<String>()
        val failed = big.map { it.path to "حجم الملف يتجاوز حد GitHub البالغ 100 ميغابايت" }
            .toMutableList()
        val retryQueue = mutableListOf<Item>()
        var commits = 0

        suspend fun commitBatch(entries: List<BlobOutcome>): Boolean {
            val tree = org.json.JSONArray()
            val paths = mutableListOf<String>()
            for (entry in entries) {
                if (entry.sha != null) {
                    tree.put(
                        org.json.JSONObject()
                            .put("path", entry.item.path).put("mode", "100644")
                            .put("type", "blob").put("sha", entry.sha),
                    )
                    paths.add(entry.item.path)
                }
            }
            if (tree.length() == 0) return false
            // 1.9.4: عند 422 «Update is not a fast forward» نعيد مزامنة الرأس ثم نعيد الالتزام (حتى 3 محاولات)
            var lastError: GitHubClient.GitHubException? = null
            for (attempt in 0 until 3) {
                try {
                    val newTree = api.createTree(repoFullName, treeSha, tree)
                    val commit = api.createCommit(
                        repoFullName, "$commitMessage (${tree.length()} ملف)",
                        newTree.getString("sha"), listOf(headSha),
                    )
                    api.updateRef(repoFullName, branch, commit.getString("sha"))
                    headSha = commit.getString("sha")
                    treeSha = newTree.getString("sha")
                    commits++
                    uploaded.addAll(paths)
                    return true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GitHubClient.GitHubException) {
                    lastError = e
                    val fastForward = e.code == 422 &&
                        (e.message ?: "").contains("fast forward", ignoreCase = true)
                    if (!fastForward) throw e
                    // إعادة المزامنة: قراءة المرجع الحالي وتحديث الرأس والشجرة ثم إعادة المحاولة
                    try {
                        val ref = api.getRef(repoFullName, branch)
                        val fresh = api.getCommit(
                            repoFullName, ref.getJSONObject("object").getString("sha"),
                        )
                        headSha = fresh.getString("sha")
                        treeSha = fresh.getJSONObject("tree").getString("sha")
                    } catch (_: Exception) {
                        // نُبقي القيم القديمة ونحاول مرة أخرى على أي حال
                    }
                    delay(600L * (attempt + 1))
                }
            }
            throw lastError ?: IllegalStateException("فشل الالتزام")
        }

        val total = inputItems.size
        var processed = 0
        var bytesDone = 0L

        suspend fun flush(current: List<Item>) {
            if (current.isEmpty()) return
            val entries = createBlobs(api, repoFullName, current)
            for (entry in entries) {
                processed++
                if (entry.sha != null) bytesDone += entry.item.size
                onProgress(processed, total, entry.item.path, bytesDone)
            }
            commitBatch(entries)
            for (entry in entries) {
                if (entry.sha == null) {
                    failed.add(entry.item.path to shortError(entry.error ?: "خطأ غير معروف"))
                    if (entry.rateLimited) retryQueue.add(entry.item)
                }
            }
            delay(COMMIT_PAUSE_MS)
        }

        var batch = mutableListOf<Item>()
        var batchBytes = 0L

        inputItems.forEach { item ->
            if (item.size > SAFE_LIMIT) return@forEach
            batch.add(item)
            batchBytes += item.size
            if (batch.size >= 150 || batchBytes >= 20L * 1024 * 1024) {
                val toSend = batch.toList()
                batch = mutableListOf()
                batchBytes = 0L
                flush(toSend)
            }
        }
        flush(batch.toList())
        batch = mutableListOf()

        var pass = 0
        while (retryQueue.isNotEmpty() && pass < FINAL_RETRY_PASSES) {
            pass++
            val pending = retryQueue.toList()
            retryQueue.clear()
            onProgress(processed, total, "⟳ تهوئة قبل إعادة محاولة ${pending.size} ملف…", bytesDone)
            delay(RATE_LIMIT_COOLDOWN_MS)

            val outcomes = mutableListOf<BlobOutcome>()
            var sub = mutableListOf<Item>()
            for (item in pending) {
                sub.add(item)
                if (sub.size >= 150) {
                    outcomes.addAll(createBlobs(api, repoFullName, sub))
                    sub = mutableListOf()
                }
            }
            outcomes.addAll(createBlobs(api, repoFullName, sub))

            val retried = outcomes.filter { it.sha != null }
            if (retried.isNotEmpty()) {
                commitBatch(retried)
                bytesDone += retried.sumOf { it.item.size }
                val okPaths = retried.map { it.item.path }.toSet()
                failed.removeAll { it.first in okPaths }
                onProgress(processed, total, "⟳ تم إجازة ${retried.size} ملف", bytesDone)
            }
            for (entry in outcomes) {
                if (entry.sha == null) {
                    failed.add(entry.item.path to shortError(entry.error ?: "خطأ غير معروف"))
                    if (entry.rateLimited) retryQueue.add(entry.item)
                }
            }
            delay(COMMIT_PAUSE_MS)
        }

        Result(uploaded, failed, commits)
    }
}
