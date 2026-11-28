package io.github.mahdi.uploader

import java.io.File

object SecurityUtils {

    private const val MAX_TEXT_SCAN = 512L * 1024

    private val sensitiveNames = listOf(
        ".env", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
        "credentials.json", "secrets.json", "firebase_config.json",
        "google-services.json", "client_secret.json",
    )
    private val sensitiveExtensions = setOf(
        "pem", "key", "p12", "pfx", "jks", "keystore", "kdbx", "pk8", "crt.bak", "htpasswd",
    )
    private val suspiciousNameFragments = listOf("password", "secret", "apikey", "api_key", "privatekey", "private_key")

    private val secretPatterns = listOf(
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        Regex("gh[pousr]_[A-Za-z0-9]{30,}"),
        Regex("AKIA[0-9A-Z]{16}"),
        Regex("sk-[A-Za-z0-9]{20,}"),
        Regex("AIza[0-9A-Za-z_\\-]{30,}"),
        Regex("xox[bpa]-[A-Za-z0-9\\-]{20,}"),
    )

    fun findSensitive(items: List<UploadEngine.Item>, maxTextScan: Long = 512 * 1024): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        for (item in items) {
            val name = item.path.substringAfterLast('/')
            val reason = nameReason(name)
                ?: textReason(item)
            if (reason != null) found[item.path] = reason
        }
        return found
    }

    private fun nameReason(name: String): String? {
        val lower = name.lowercase()
        if (lower in sensitiveNames) return "اسم الملف مصنَّف كبيانات حساسة"
        if (lower.endsWith(".env") || lower.contains(".env.")) return "ملف متغيرات بيئة"
        val ext = lower.substringAfterLast('.', "")
        if (ext in sensitiveExtensions) return "امتداد مفتاح/شهادة"
        if (suspiciousNameFragments.any { it in lower.replace(" ", "").replace("-", "").replace("_", "") })
            return "اسم يوحي بوجود بيانات دخول"
        return null
    }

    private fun textReason(item: UploadEngine.Item): String? {
        val f = item.file
        if (!f.isFile) return null
        if (f.length() <= 0 || f.length() > MAX_TEXT_SCAN) return null
        return try {
            val head = f.readText(Charsets.UTF_8).take(256 * 1024)
            val lineCount = head.count { it == '\n' }
            if (lineCount > 16_000) null
            else secretPatterns.firstOrNull { it.containsMatchIn(head) }?.let { "يحتوي نصاً يشبه مفتاحاً سرّياً" }
        } catch (_: Exception) {
            null
        }
    }

    fun suggestCommitMessage(paths: List<String>): String {
        if (paths.isEmpty()) return ""
        val dirs = paths.map { it.substringBefore('/', "") }.filter { it.isNotEmpty() }
        val commonDir = dirs.distinct().singleOrNull()
        val n = paths.size
        val names = paths.map { it.substringAfterLast('/') }
        return when {
            n == 1 -> "إضافة ${names[0]}"
            commonDir != null -> "إضافة مجلد $commonDir ($n ملف)"
            n <= 3 -> "إضافة: ${names.joinToString("، ")}"
            else -> "إضافة $n ملفات: ${names.take(3).joinToString("، ")}…"
        }
    }
}
