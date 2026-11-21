package io.github.mahdi.uploader

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Store {
    private const val PREFS = "uploader_prefs_secure"
    private const val LEGACY_PREFS = "uploader_prefs"
    private const val ACCOUNT_SEP = "\u0001"

    private var cached: SharedPreferences? = null
    private var cachedFailed = false

    @Synchronized
    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        if (!cachedFailed) {
            try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val enc = EncryptedSharedPreferences.create(
                    context, PREFS, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                migrateLegacy(context, enc)
                cached = enc
                return enc
            } catch (_: Exception) {
                cachedFailed = true
            }
        }
        val plain = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateLegacy(context, plain)
        cached = plain
        return plain
    }

    private fun migrateLegacy(context: Context, target: SharedPreferences) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        val edit = target.edit()
        legacy.all.entries.forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    edit.putStringSet(key, value as Set<String>)
                }
            }
        }
        edit.apply()
        legacy.edit().clear().apply()
    }

    fun token(context: Context): String = prefs(context).getString("token", "").orEmpty()

    fun setToken(context: Context, value: String) =
        prefs(context).edit().putString("token", value).apply()

    fun clientId(context: Context): String = prefs(context).getString("client_id", "").orEmpty()

    fun setClientId(context: Context, value: String) =
        prefs(context).edit().putString("client_id", value).apply()

    fun login(context: Context): String = prefs(context).getString("login", "").orEmpty()

    fun setLogin(context: Context, value: String) =
        prefs(context).edit().putString("login", value).apply()

    private fun accountsKey() = "saved_accounts"

    fun accounts(context: Context): List<Pair<String, String>> =
        prefs(context).getStringSet(accountsKey(), emptySet())
            .orEmpty()
            .mapNotNull { entry ->
                val i = entry.indexOf(ACCOUNT_SEP)
                if (i <= 0) null
                else entry.substring(0, i) to entry.substring(i + ACCOUNT_SEP.length)
            }
            .sortedBy { it.first }

    fun addAccount(context: Context, login: String, token: String) {
        if (login.isEmpty() || token.isEmpty()) return
        removeAccount(context, login)
        val updated = prefs(context).getStringSet(accountsKey(), emptySet())
            .orEmpty() + "$login$ACCOUNT_SEP$token"
        prefs(context).edit().putStringSet(accountsKey(), updated).apply()
    }

    fun removeAccount(context: Context, login: String) {
        val updated = prefs(context).getStringSet(accountsKey(), emptySet())
            .orEmpty()
            .filterNot { it.startsWith("$login$ACCOUNT_SEP") }
            .toSet()
        prefs(context).edit().putStringSet(accountsKey(), updated).apply()
    }
}
