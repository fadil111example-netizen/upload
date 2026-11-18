package com.termux.githubuploader.core

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.util.Locale

/**
 * Pengganti rangkaian perintah git di upload.sh:
 *   git init / config / remote add / fetch / reset / add -A / commit / push / ls-remote
 *
 * Semua pekerjaan dilakukan di salinan project di penyimpanan privat aplikasi,
 * jadi folder asli milik pengguna tidak pernah disentuh (sama seperti skrip).
 */
class GitEngine(private val reporter: Reporter) {

    data class StageResult(val changed: Int, val examples: List<String>)

    fun open(root: File, branch: String): Git {
        val git = Git.init()
            .setDirectory(root)
            .setInitialBranch(branch)
            .call()
        val config = git.repository.config
        config.setBoolean("core", null, "filemode", false)
        config.setBoolean("core", null, "autocrlf", false)
        config.setBoolean("core", null, "quotePath", false)
        config.save()
        return git
    }

    fun addRemote(git: Git, url: String) {
        val config = git.repository.config
        config.setString("remote", "origin", "url", url)
        config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
        config.save()
    }

    /** Setara mkdir -p "$GITDIR/info"; : > info/exclude lalu excl_add(). */
    fun writeExcludes(git: Git, patterns: Collection<String>) {
        val info = File(git.repository.directory, "info"); info.mkdirs()
        val file = File(info, "exclude")
        file.writeText(patterns.joinToString("\n", postfix = if (patterns.isEmpty()) "" else "\n"))
    }

    private fun credentials(token: String) = UsernamePasswordCredentialsProvider("x-access-token", token)

    /** null = branch belum ada di remote. */
    fun remoteTip(git: Git, branch: String, token: String): ObjectId? {
        val refs: Collection<Ref> = try {
            git.lsRemote()
                .setRemote("origin")
                .setHeads(true)
                .setCredentialsProvider(credentials(token))
                .setTimeout(45)
                .call()
        } catch (e: GitAPIException) {
            throw transportError(e)
        }
        val wanted = "refs/heads/$branch"
        return refs.firstOrNull { it.name == wanted }?.objectId
    }

    fun fetchAndReset(git: Git, branch: String, token: String, tip: ObjectId) {
        reporter.info("Mengambil history remote...")
        try {
            git.fetch()
                .setRemote("origin")
                .setRefSpecs(RefSpec("+refs/heads/$branch:refs/remotes/origin/$branch"))
                .setCredentialsProvider(credentials(token))
                .setProgressMonitor(monitor("Fetch"))
                .setTimeout(120)
                .call()
            git.reset()
                .setMode(ResetCommand.ResetType.MIXED)
                .setRef(tip.name)
                .call()
        } catch (e: GitAPIException) {
            throw UploadException("Gagal mengambil history remote: ${e.message}")
        }
    }

    /** Setara `git add -A` (atau `git add -A --ignore-removal`). */
    fun stageAll(git: Git, keepRemoteDeletions: Boolean) {
        try {
            git.add().addFilepattern(".").call()
            if (!keepRemoteDeletions) {
                // Menghapus file yang sudah tidak ada di project.
                git.add().setUpdate(true).addFilepattern(".").call()
            }
        } catch (e: GitAPIException) {
            throw UploadException("git add gagal: ${e.message}")
        }
    }

    fun stagedSummary(git: Git, limit: Int = 15): StageResult {
        val status = try {
            git.status().call()
        } catch (e: GitAPIException) {
            throw UploadException("Gagal membaca status git: ${e.message}")
        }
        val all = ArrayList<String>()
        all += status.added
        all += status.changed
        all += status.modified
        all += status.removed
        all += status.missing
        all += status.untracked
        all.sort()
        return StageResult(all.size, all.take(limit))
    }

    fun commit(git: Git, message: String, authorName: String, authorEmail: String): ObjectId {
        try {
            return git.commit()
                .setMessage(message)
                .setAuthor(authorName, authorEmail)
                .setCommitter(authorName, authorEmail)
                .setSign(false)
                .call()
                .id
        } catch (e: GitAPIException) {
            throw UploadException("git commit gagal: ${e.message}")
        }
    }

    /**
     * Push dengan 3 percobaan untuk gangguan jaringan (sama seperti loop di skrip).
     * Mengembalikan true bila server sudah menerima commit.
     */
    fun push(git: Git, branch: String, token: String): Boolean {
        var attempt = 1
        var lastMessage = ""
        while (attempt <= 3) {
            reporter.info("Mengirim ke GitHub... (percobaan $attempt/3)")
            try {
                val results = git.push()
                    .setRemote("origin")
                    .setRefSpecs(RefSpec("HEAD:refs/heads/$branch"))
                    .setCredentialsProvider(credentials(token))
                    .setProgressMonitor(monitor("Upload"))
                    .setTimeout(0)
                    .call()
                val updates = results.flatMap { it.remoteUpdates }
                if (updates.all { it.status == RemoteRefUpdate.Status.OK || it.status == RemoteRefUpdate.Status.UP_TO_DATE }) {
                    return true
                }
                val bad = updates.firstOrNull { it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE }
                lastMessage = "${bad?.status} ${bad?.message.orEmpty()}".trim()
                if (bad?.status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD) {
                    throw UploadException("Push ditolak (non-fast-forward). Ada commit lain di branch ini; coba lagi atau ganti branch.")
                }
                if (bad?.status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED) {
                    throw UploadException("Branch berubah saat upload. Jalankan ulang upload.")
                }
            } catch (e: UploadException) {
                throw e
            } catch (e: Exception) {
                lastMessage = e.message.orEmpty()
                if (!isRetryable(lastMessage)) throw mapPushError(lastMessage)
            }

            if (isRetryable(lastMessage) && attempt < 3) {
                reporter.warn("Gangguan jaringan (percobaan $attempt/3), mengulang...")
                sleep(attempt * 4000L)
                attempt++
            } else {
                break
            }
        }
        throw mapPushError(lastMessage)
    }

    fun verifyRemote(git: Git, branch: String, token: String, local: ObjectId): Boolean {
        return try {
            val remote = remoteTip(git, branch, token)
            remote != null && remote == local
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------- helpers --
    private fun monitor(title: String): ProgressMonitor = object : ProgressMonitor {
        override fun start(totalTasks: Int) = Unit
        override fun beginTask(name: String?, totalWork: Int) {
            reporter.progress("$title: $name")
        }

        override fun update(completed: Int) {
            if (Thread.currentThread().isInterrupted) throw UploadException("Dibatalkan oleh pengguna.")
            if (completed % 25 != 0) return
            reporter.progress("$title: $completed objek")
        }

        override fun endTask() {
            reporter.progress(null)
        }

        override fun isCancelled(): Boolean = Thread.currentThread().isInterrupted
    }

    private fun transportError(e: Exception): UploadException {
        val m = e.message.orEmpty().lowercase(Locale.ROOT)
        return when {
            m.contains("authentication") || m.contains("not authorized") || m.contains("401") ->
                UploadException("Token ditolak GitHub. Token butuh izin tulis (scope 'repo' / Contents: write). Simpan token baru di menu Token.")
            m.contains("could not resolve") || m.contains("unknown host") || m.contains("timed out") ->
                UploadException("Tidak ada koneksi internet / GitHub tidak bisa dihubungi.")
            else -> UploadException("Gagal menghubungi GitHub: ${e.message}")
        }
    }

    /**
     * Padanan syarat retry di skrip:
     *   grep -qiE 'could not resolve|early EOF|...|HTTP 5[0-9][0-9]'
     *   DAN BUKAN 'GH013|push protection|authentication failed|403|401'
     */
    private fun isRetryable(message: String): Boolean {
        if (isAuthOrProtection(message)) return false
        return isNetworkError(message)
    }

    private fun isAuthOrProtection(message: String): Boolean {
        val m = message.lowercase(Locale.ROOT)
        return m.contains("gh013") || m.contains("push protection") ||
            m.contains("authentication failed") || m.contains("403") || m.contains("401")
    }

    private fun isNetworkError(message: String): Boolean {
        val m = message.lowercase(Locale.ROOT)
        return m.contains("could not resolve") || m.contains("early eof") || m.contains("rpc failed") ||
            m.contains("timed out") || m.contains("connection reset") || m.contains("connection refused") ||
            m.contains("unable to access") || m.contains("remote end hung up") ||
            Regex("http 5[0-9][0-9]").containsMatchIn(m)
    }

    private fun mapPushError(message: String): UploadException {
        val m = message.lowercase(Locale.ROOT)
        return when {
            m.contains("gh013") || m.contains("push protection") ->
                UploadException("GitHub memblokir push karena ada rahasia (token/key) di file. Jalankan ulang lalu pilih 'Lewati file sensitif'.")
            m.contains("large files") || m.contains("exceeds") || m.contains("gh001") ->
                UploadException("Ada file terlalu besar untuk GitHub (batas 100MB).")
            m.contains("authentication failed") || m.contains("invalid credentials") || m.contains("403") || m.contains("401") || m.contains("denied") ->
                UploadException("Push ditolak. Token butuh izin tulis (scope 'repo' / Contents: write). Simpan token baru di menu Token.")
            message.isBlank() -> UploadException("Push gagal tanpa pesan dari GitHub.")
            else -> UploadException("Push gagal: $message")
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        fun defaultBranchName(): String = Constants.MASTER
    }
}
