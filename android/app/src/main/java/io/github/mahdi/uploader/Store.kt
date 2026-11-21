package io.github.mahdi.uploader

import android.content.Context

object Store {
    private const val PREFS = "uploader_prefs"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(context: Context): String = prefs(context).getString("token", "").orEmpty()

    fun setToken(context: Context, value: String) =
        prefs(context).edit().putString("token", value).apply()

    fun clientId(context: Context): String = prefs(context).getString("client_id", "").orEmpty()

    fun setClientId(context: Context, value: String) =
        prefs(context).edit().putString("client_id", value).apply()

    fun login(context: Context): String = prefs(context).getString("login", "").orEmpty()

    fun setLogin(context: Context, value: String) =
        prefs(context).edit().putString("login", value).apply()
}
