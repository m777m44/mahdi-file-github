package io.github.mahdi.uploader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 🗂️ سجل روابط المشاركة المباشرة — محفوظ محلياً (آخر 100 رابط) */
object ShareHistory {

    class Entry(val name: String, val url: String, val host: String, val life: String, val time: Long)

    private const val PREFS = "share_history"
    private const val KEY = "items"
    private const val MAX = 100

    fun load(ctx: Context): List<Entry> = try {
        val arr = JSONArray(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.optString("name"), o.optString("url"),
                o.optString("host"), o.optString("life"), o.optLong("time"))
        }
    } catch (_: Exception) { emptyList() }

    fun add(ctx: Context, name: String, url: String, host: String, life: String) {
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
            val o = JSONObject()
                .put("name", name).put("url", url)
                .put("host", host).put("life", life)
                .put("time", System.currentTimeMillis())
            val newArr = JSONArray().put(o)
            for (i in 0 until minOf(arr.length(), MAX - 1)) newArr.put(arr.getJSONObject(i))
            prefs.edit().putString(KEY, newArr.toString()).apply()
        } catch (_: Exception) { }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
