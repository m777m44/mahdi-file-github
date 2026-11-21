package io.github.mahdi.uploader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

object UploadEngine {

    const val HARD_LIMIT = 100L * 1024 * 1024      // GitHub hard limit per file
    private const val SAFE_LIMIT = 90L * 1024 * 1024

    class Item(val path: String, val size: Long, val read: () -> ByteArray)

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

    fun readBytes(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("تعذر قراءة الملف: $uri")

    fun readBytesFile(file: File): ByteArray = file.readBytes()

    suspend fun extractZip(context: Context, zipUri: Uri, zipName: String): List<Item> =
        withContext(Dispatchers.IO) {
            val cacheDir = File(context.cacheDir, "zip_${System.nanoTime()}")
            cacheDir.mkdirs()
            val zipFile = File(cacheDir, cleanPath(zipName).substringAfterLast('/')
                .ifEmpty { "archive.zip" })
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                zipFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw Exception("تعذر قراءة ملف الـ zip")

            val base = cleanPath(zipName).removeSuffix(".zip").removeSuffix(".ZIP")
            val items = mutableListOf<Item>()
            ZipFile(zipFile).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val rel = cleanPath(entry.name)
                    if (isJunk(rel)) continue
                    val resolved = File(cacheDir, rel).canonicalFile
                    if (!resolved.path.startsWith(cacheDir.canonicalFile.path)) continue
                    resolved.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input ->
                        resolved.outputStream().use { output -> input.copyTo(output) }
                    }
                    val target = if (base.isNotEmpty()) "$base/$rel" else rel
                    val f = resolved
                    items.add(Item(target, f.length()) { f.readBytes() })
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
        onProgress: (done: Int, total: Int, current: String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val items = mutableListOf<Item>()
        for (item in inputItems) {
            if (item.size <= SAFE_LIMIT) items.add(item)
        }
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
        val failed = big.map { it.path to "حجم الملف يتجاوز حد GitHub البالغ 100 ميجابايت" }.toMutableList()
        var commits = 0

        var batch = mutableListOf<Item>()
        var batchBytes = 0L

        suspend fun flush() {
            if (batch.isEmpty()) return
            val current = batch.toList()
            val doneBefore = uploaded.size + failed.size
            val entries = coroutineScope {
                val semaphore = Semaphore(6)
                current.map { item ->
                    async {
                        semaphore.withPermit {
                            try {
                                val blob = api.createBlob(repoFullName, item.read())
                                Triple(item.path, blob.getString("sha"), null as String?)
                            } catch (e: Exception) {
                                Triple(item.path, null as String?, e.message ?: "خطأ غير معروف")
                            }
                        }
                    }
                }.awaitAll()
            }
            val tree = org.json.JSONArray()
            for ((path, sha, error) in entries) {
                if (sha != null) {
                    tree.put(
                        org.json.JSONObject()
                            .put("path", path).put("mode", "100644")
                            .put("type", "blob").put("sha", sha),
                    )
                    uploaded.add(path)
                } else if (error != null) {
                    failed.add(path to error)
                }
                onProgress(doneBefore + uploaded.size + failed.size - (big.size),
                    inputItems.size, path)
            }
            if (tree.length() > 0) {
                val newTree = api.createTree(repoFullName, treeSha, tree)
                val commit = api.createCommit(
                    repoFullName, "$commitMessage (${tree.length()} ملف)",
                    newTree.getString("sha"), listOf(headSha),
                )
                api.updateRef(repoFullName, branch, commit.getString("sha"))
                headSha = commit.getString("sha")
                treeSha = newTree.getString("sha")
                commits++
            }
            batch = mutableListOf()
            batchBytes = 0L
        }

        inputItems.forEachIndexed { index, item ->
            if (item.size > SAFE_LIMIT) return@forEachIndexed
            batch.add(item)
            batchBytes += item.size
            onProgress(index + 1, inputItems.size, item.path)
            if (batch.size >= 150 || batchBytes >= 20L * 1024 * 1024) flush()
        }
        flush()

        Result(uploaded, failed, commits)
    }
}
