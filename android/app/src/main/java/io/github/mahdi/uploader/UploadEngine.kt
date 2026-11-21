package io.github.mahdi.uploader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

object UploadEngine {

    const val HARD_LIMIT = 100L * 1024 * 1024      // GitHub hard limit per file
    private const val SAFE_LIMIT = 90L * 1024 * 1024

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
        onProgress: (done: Int, total: Int, current: String) -> Unit,
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
        val failed = big.map { it.path to "حجم الملف يتجاوز حد GitHub البالغ 100 ميجابايت" }
            .toMutableList()
        var commits = 0
        var processed = 0

        var batch = mutableListOf<Item>()
        var batchBytes = 0L

        suspend fun flush() {
            if (batch.isEmpty()) return
            val current = batch.toList()
            val semaphore = kotlinx.coroutines.sync.Semaphore(6)
            val entries = coroutineScope {
                current.map { item ->
                    async {
                        semaphore.acquire()
                        try {
                            try {
                                val blob = api.createBlob(repoFullName, item.file)
                                Triple(item.path, blob.getString("sha"), null as String?)
                            } catch (e: Exception) {
                                Triple(item.path, null as String?, e.message ?: "خطأ غير معروف")
                            }
                        } finally {
                            semaphore.release()
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
                processed++
                onProgress(processed, inputItems.size, path)
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

        inputItems.forEach { item ->
            if (item.size > SAFE_LIMIT) return@forEach
            batch.add(item)
            batchBytes += item.size
            if (batch.size >= 150 || batchBytes >= 20L * 1024 * 1024) flush()
        }
        flush()

        Result(uploaded, failed, commits)
    }
}
