package com.termux.githubuploader.core

import android.content.Context

/**
 * Penyimpanan token. Pengganti $HOME/.upload_gh_token + chmod 600 di skrip:
 * file berada di penyimpanan privat aplikasi sehingga tidak bisa dibaca app lain.
 */
class TokenStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("uploader", Context.MODE_PRIVATE)

    fun token(): String? = prefs.getString(KEY_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun login(): String? = prefs.getString(KEY_LOGIN, null)?.takeIf { it.isNotEmpty() }

    fun save(token: String, login: String?) {
        prefs.edit().putString(KEY_TOKEN, token.trim()).apply()
        if (login != null) prefs.edit().putString(KEY_LOGIN, login).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_LOGIN).apply()
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_LOGIN = "login"
    }
}
