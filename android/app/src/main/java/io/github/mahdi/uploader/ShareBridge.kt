package io.github.mahdi.uploader

/** 🔁 جسر الإشارات بين مركز الرفع المباشر والشاشة الرئيسية */
object ShareBridge {
    @Volatile
    var pendingGithubUpload: Boolean = false
}
