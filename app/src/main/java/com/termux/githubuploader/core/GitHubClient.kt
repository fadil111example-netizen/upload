package com.termux.githubuploader.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Ringkasan akun pemilik token (hasil GET /user). */
data class AccountInfo(
    val login: String,
    val name: String?,
    val scopes: String?,
    val tokenExpiration: String?
) {
    /** true bila token classic tidak punya scope repo/public_repo. */
    val missingRepoScope: Boolean
        get() {
            val s = scopes ?: return false
            if (s.isBlank()) return false
            val list = s.split(',').map { it.trim() }
            return !list.contains("repo") && !list.contains("public_repo")
        }
}

/** Info repo yang sudah ada di GitHub. */
data class RepoInfo(
    val name: String,
    val defaultBranch: String,
    val private: Boolean,
    val htmlUrl: String
)

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Err(val code: Int, val message: String) : ApiResult<Nothing>()

    val isOk: Boolean get() = this is Ok
}

/**
 * Pengganti seluruh pemakaian `curl` + helper api()/json_get() di upload.sh.
 * Memakai OkHttp karena HttpURLConnection Android tidak mendukung PATCH.
 */
class GitHubClient(private val token: String?) {

    var login: String? = null
        private set

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private class Response(
        val code: Int,
        val body: String,
        val headers: okhttp3.Headers
    ) {
        fun header(name: String): String? = headers[name]

        fun json(): JSONObject? = try {
            if (body.isBlank()) null else JSONObject(body)
        } catch (e: Exception) {
            null
        }

        fun message(): String {
            val m = json()?.optString("message").orEmpty()
            if (m.isNotBlank()) return m
            return body.take(300).ifBlank { "HTTP $code" }
        }
    }

    private fun request(method: String, url: String, jsonBody: String? = null, timeoutSec: Long = 30): Response {
        var lastError: IOException? = null
        repeat(2) { attempt ->
            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "GitHubUploader-Android/2.0")
                    .header("Connection", "close")
                if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")

                val body = when {
                    jsonBody != null -> jsonBody.toRequestBody(JSON)
                    method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(JSON)
                    else -> null
                }
                val req = builder.method(method, body).build()
                val client = if (timeoutSec == 30L) http else http.newBuilder()
                    .readTimeout(timeoutSec, TimeUnit.SECONDS)
                    .build()
                client.newCall(req).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    return Response(res.code, text, res.headers)
                }
            } catch (e: IOException) {
                lastError = e
                sleep(1200L * (attempt + 1))
            }
        }
        throw UploadException("Tidak ada koneksi internet / GitHub tidak bisa dihubungi.\n${lastError?.message ?: ""}".trim())
    }

    // ------------------------------------------------------------- ACCOUNT --
    fun checkAccount(): ApiResult<AccountInfo> {
        val r = try {
            request("GET", "$API/user")
        } catch (e: UploadException) {
            return ApiResult.Err(0, e.message ?: "Tidak ada koneksi internet.")
        }
        if (r.code != 200) return ApiResult.Err(r.code, r.message())
        val j = r.json()
        val user = j?.optString("login").orEmpty()
        if (user.isBlank()) return ApiResult.Err(r.code, "GitHub tidak mengembalikan data login.")
        login = user
        return ApiResult.Ok(
            AccountInfo(
                login = user,
                name = j?.optString("name")?.takeIf { it.isNotBlank() },
                scopes = r.header("x-oauth-scopes"),
                tokenExpiration = r.header("github-authentication-token-expiration")
            )
        )
    }

    // ---------------------------------------------------------------- REPO --
    fun getRepo(owner: String, name: String): ApiResult<RepoInfo?> {
        val r = try {
            request("GET", "$API/repos/$owner/$name")
        } catch (e: UploadException) {
            return ApiResult.Err(0, e.message ?: "Tidak ada koneksi internet.")
        }
        return when (r.code) {
            200 -> {
                val j = r.json() ?: return ApiResult.Err(200, "Balasan repo tidak bisa dibaca.")
                ApiResult.Ok(
                    RepoInfo(
                        name = j.optString("name", name),
                        defaultBranch = j.optString("default_branch", "main").ifBlank { "main" },
                        private = j.optBoolean("private", true),
                        htmlUrl = j.optString("html_url", "https://github.com/$owner/$name")
                    )
                )
            }
            404 -> ApiResult.Ok(null) // belum ada -> nanti dibuat
            401 -> ApiResult.Err(401, "Token ditolak. Simpan token baru lewat menu Token.")
            403, 429 -> ApiResult.Err(r.code, "GitHub membatasi request (HTTP ${r.code}). Coba lagi beberapa saat.")
            0 -> ApiResult.Err(0, "Tidak ada koneksi internet.")
            else -> ApiResult.Err(r.code, "Gagal cek repo (HTTP ${r.code}): ${r.message()}")
        }
    }

    fun createRepo(name: String, description: String, isPrivate: Boolean): ApiResult<String> {
        val payload = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("private", isPrivate)
            .put("auto_init", false)
            .toString()
        val r = try {
            request("POST", "$API/user/repos", payload)
        } catch (e: UploadException) {
            return ApiResult.Err(0, e.message ?: "Koneksi terputus.")
        }
        return when (r.code) {
            201 -> {
                val url = r.json()?.optString("html_url").orEmpty()
                ApiResult.Ok(url.ifBlank { "https://github.com/${login ?: ""}/$name" })
            }
            401 -> ApiResult.Err(401, "Token ditolak saat membuat repo. Simpan token baru lewat menu Token.")
            403 -> ApiResult.Err(
                403,
                "Token tidak punya izin membuat repo (butuh scope 'repo' / fine-grained: Administration write)."
            )
            422 -> ApiResult.Err(422, "GitHub menolak pembuatan repo: ${r.message()}")
            0 -> ApiResult.Err(0, "Koneksi terputus.")
            else -> ApiResult.Err(r.code, "Gagal membuat repo (HTTP ${r.code}): ${r.message()}")
        }
    }

    fun updateDescription(owner: String, name: String, description: String): ApiResult<Unit> {
        val payload = JSONObject().put("description", description).toString()
        val r = try {
            request("PATCH", "$API/repos/$owner/$name", payload)
        } catch (e: UploadException) {
            return ApiResult.Err(0, e.message ?: "Koneksi terputus.")
        }
        return if (r.code == 200) ApiResult.Ok(Unit)
        else ApiResult.Err(r.code, "Gagal mengubah deskripsi (HTTP ${r.code}).")
    }

    // ----------------------------------------------------------------- MISC --
    fun rateLimitRemaining(): Int? = try {
        val r = request("GET", "$API/rate_limit", timeoutSec = 12)
        r.json()?.optJSONObject("rate")?.optInt("remaining")
    } catch (e: Exception) {
        null
    }

    companion object {
        const val API = "https://api.github.com"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun hasNetwork(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        fun sleep(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}