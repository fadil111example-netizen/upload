package com.termux.githubuploader.core

import android.net.Uri

/** Jenis project yang dipilih pengguna. */
enum class TargetKind { FOLDER, ARCHIVE }

/** Satu project yang akan diupload. */
data class Target(
    val uri: Uri,
    val displayName: String,
    val kind: TargetKind
)

/** Semua opsi yang di skrip dipasang lewat argumen (--public, --name, dst). */
data class UploadOptions(
    val repoName: String? = null,
    val description: String = "",
    val branch: String? = null,
    val commitMessage: String? = null,
    val isPublic: Boolean = false,
    val dryRun: Boolean = false,
    val includeIgnored: Boolean = false,
    val keepRemoteDeletions: Boolean = false,
    val autoConfirm: Boolean = false
)

enum class LogLevel { INFO, OK, WARN, ERROR }

/** Hasil akhir satu project. */
data class UploadOutcome(
    val project: String,
    val repoUrl: String? = null,
    val fileCount: Int = 0,
    val seconds: Int = 0,
    val skippedBig: Int = 0,
    val upToDate: Boolean = false,
    val dryRun: Boolean = false,
    val error: String? = null
) {
    val success: Boolean get() = error == null
}

/** Kesalahan yang pesannya sudah layak ditampilkan ke pengguna. */
class UploadException(message: String) : Exception(message)

/** Kanal log: dipakai engine untuk menulis ke layar. */
interface Reporter {
    fun info(message: String)
    fun ok(message: String)
    fun warn(message: String)
    fun error(message: String)
    fun progress(text: String?)
}

/** Reporter yang membuang semua output (untuk unit test / pemakaian diam). */
object SilentReporter : Reporter {
    override fun info(message: String) = Unit
    override fun ok(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String) = Unit
    override fun progress(text: String?) = Unit
}
