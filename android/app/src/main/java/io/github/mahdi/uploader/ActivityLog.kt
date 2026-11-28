package io.github.mahdi.uploader

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val time: Long,
    val repo: String,
    val branch: String,
    val uploaded: Int,
    val failed: Int,
)

object ActivityLog {
    private const val KEY = "activity_log"
    private const val MAX_ENTRIES = 100

    fun log(context: Context, repo: String, branch: String, uploaded: Int, failed: Int) {
        val arr = JSONArray(raw(context))
        val entry = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("repo", repo)
            .put("branch", branch)
            .put("uploaded", uploaded)
            .put("failed", failed)
        val newArr = JSONArray()
        newArr.put(entry)
        for (i in 0 until minOf(arr.length(), MAX_ENTRIES - 1)) newArr.put(arr.getJSONObject(i))
        prefs(context).edit().putString(KEY, newArr.toString()).apply()
    }

    fun history(context: Context): List<LogEntry> {
        val arr = JSONArray(raw(context))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            LogEntry(
                o.optLong("time"), o.optString("repo"), o.optString("branch"),
                o.optInt("uploaded"), o.optInt("failed"),
            )
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().putString(KEY, "[]").apply()
    }

    fun stats(context: Context): Triple<Int, Int, Int> {
        val hs = history(context)
        val uploads = hs.size
        val files = hs.sumOf { it.uploaded }
        val cleanRuns = hs.count { it.failed == 0 }
        val rate = if (uploads == 0) 0 else (cleanRuns * 100 / uploads)
        return Triple(uploads, files, rate)
    }

    private fun raw(context: Context): String =
        prefs(context).getString(KEY, "[]") ?: "[]"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("uploader_log", Context.MODE_PRIVATE)
}

object ActivityLogDialog {

    fun show(context: Activity) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_activity_log, null)
        val statsUploads = view.findViewById<TextView>(R.id.statUploads)
        val statsFiles = view.findViewById<TextView>(R.id.statFiles)
        val statsRate = view.findViewById<TextView>(R.id.statRate)
        val list = view.findViewById<RecyclerView>(R.id.logList)
        val empty = view.findViewById<TextView>(R.id.logEmpty)

        val (uploads, files, rate) = ActivityLog.stats(context)
        statsUploads.text = uploads.toString()
        statsFiles.text = files.toString()
        statsRate.text = "$rate%"

        val entries = ActivityLog.history(context)
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = LogAdapter(entries)

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.log_title))
            .setView(view)
            .setNeutralButton(context.getString(R.string.log_clear)) { _, _ -> ActivityLog.clear(context) }
            .setPositiveButton(context.getString(R.string.close), null)
            .show()
    }

    class LogAdapter(private val entries: List<LogEntry>) :
        RecyclerView.Adapter<LogAdapter.Holder>() {

        class Holder(root: View) : RecyclerView.ViewHolder(root) {
            val title: TextView = root.findViewById(R.id.logTitle)
            val meta: TextView = root.findViewById(R.id.logMeta)
            val badge: TextView = root.findViewById(R.id.logBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false))

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = entries[position]
            val fmt = SimpleDateFormat("yyyy/MM/dd — HH:mm", Locale.getDefault())
            holder.title.text = "${e.repo}  •  ${e.branch}"
            holder.meta.text = fmt.format(Date(e.time))
            holder.badge.text = if (e.failed == 0) {
                "✅ ${e.uploaded}"
            } else {
                "⚠️ ${e.uploaded} / ${e.failed}"
            }
            holder.badge.setTextColor(
                holder.badge.context.getColor(
                    if (e.failed == 0) R.color.accent_bright else R.color.error
                )
            )
        }
    }
}
