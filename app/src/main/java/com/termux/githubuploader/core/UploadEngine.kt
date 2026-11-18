package com.termux.githubuploader.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Padanan fungsi process_project() di upload.sh.
 *
 * Dipanggil di latar belakang (bukan main thread). Setiap project mendapat
 * workspace sendiri yang dibersihkan lagi di akhir.
 */
class UploadEngine(
    private val context: Context,
    private val reporter: Reporter
) {

    data class Plan(
        val target: Target,
        /** Salinan project di penyimpanan privat; null setelah dibersihkan. */
        var workspace: File?,
        val root: File,
        val repoName: String,
        val exists: Boolean,
        val isPublic: Boolean,
        val branch: String,
        val files: List<Scanner.Item>,
        val secrets: List<Scanner.SecretHit>,
        val tooBig: List<Scanner.Item>,
        val totalBytes: Long,
        val excludedDirs: List<String>,
        val ignorePatterns: List<String>
    )

    /** Pengganti bagian "cek repo" + "pindai file" sebelum upload. */
    fun plan(
        target: Target,
        options: UploadOptions,
        login: String,
        client: GitHubClient
    ): Plan {
        val workspace = ProjectLoader(context).newWorkspace()
        try {
            val loader = ProjectLoader(context)
            val loaded = loader.load(target, workspace, reporter)
            val nameHint = options.repoName?.takeIf { it.isNotBlank() } ?: loaded.displayName
            val repoName = Names.repoName(nameHint)
            val root = loaded.root

            reporter.info("Repository: $login/$repoName")
            val repo = when (val r = client.getRepo(login, repoName)) {
                is ApiResult.Ok -> r.value
                is ApiResult.Err -> throw UploadException(r.message)
            }
            if (repo != null) {
                reporter.warn("Repository sudah ada -> akan di-update (history lama aman, tanpa force push).")
                if (repo.private && options.isPublic) {
                    reporter.warn("Visibilitas repo lama tidak diubah (saat ini: private).")
                }
            }
            val isPublic = repo?.let { !it.private } ?: options.isPublic
            val branch = options.branch?.takeIf { it.isNotBlank() } ?: repo?.defaultBranch ?: "main"
            validateBranch(branch)

            // Sama seperti skrip: file .env/id_rsa/*.pem hanya dicek untuk repo public.
            val scan = Scanner(root, options.includeIgnored, checkCredentialNames = isPublic).scan()
            if (scan.files.isEmpty() && scan.tooBig.isEmpty()) {
                throw UploadException("Project kosong, tidak ada file untuk diupload.")
            }
            if (scan.nestedGit > 0) {
                reporter.warn("Ada ${scan.nestedGit} repo git bersarang di dalam project; foldernya bisa tampil kosong di GitHub.")
            }
            if (scan.excludedDirs.isNotEmpty()) {
                reporter.info("Tanpa .gitignore: node_modules, __pycache__, .gradle otomatis diabaikan (pakai opsi 'Ikutkan' untuk ikut).")
            }

            return Plan(
                target = target,
                workspace = workspace,
                root = root,
                repoName = repoName,
                exists = repo != null,
                isPublic = isPublic,
                branch = branch,
                files = scan.files,
                secrets = scan.secrets,
                tooBig = scan.tooBig,
                totalBytes = scan.totalBytes,
                excludedDirs = scan.excludedDirs,
                ignorePatterns = scan.ignorePatterns
            )
        } catch (e: Exception) {
            workspace.deleteRecursively()
            throw e
        }
    }

    /** Hapus salinan project setelah selesai / dibatalkan. */
    fun release(plan: Plan) {
        plan.workspace?.deleteRecursively()
        plan.workspace = null
    }

    /**
     * Upload satu project. `skipSecrets` = daftar path relatif yang tidak ikut.
     */
    fun upload(
        plan: Plan,
        options: UploadOptions,
        token: String,
        login: String,
        client: GitHubClient,
        skipSecrets: Set<String>
    ): UploadOutcome {
        val started = System.currentTimeMillis()
        var git: org.eclipse.jgit.api.Git? = null
        val engine = GitEngine(reporter)
        try {
            val root = plan.root

            // --- git init + remote ---
            val repo = engine.open(root, plan.branch)
            git = repo
            val gitUrl = "https://github.com/$login/${plan.repoName}.git"
            engine.addRemote(repo, gitUrl)

            // File yang dilewati ditulis ke info/exclude SEBELUM git add,
            // persis seperti excl_add() di skrip, jadi tidak pernah ikut commit.
            val skipped = LinkedHashSet<String>().apply {
                addAll(plan.ignorePatterns)
                plan.tooBig.forEach { add("/${Scanner.escapeIgnore(it.rel)}") }
                skipSecrets.forEach { add("/${Scanner.escapeIgnore(it)}") }
            }
            engine.writeExcludes(repo, skipped)

            // --- sambung ke history remote bila repo sudah berisi ---
            val tip = if (plan.exists) engine.remoteTip(repo, plan.branch, token) else null
            if (tip != null) engine.fetchAndReset(repo, plan.branch, token, tip)

            // --- git add -A (+ file yang dilewati) ---
            reporter.info("Memindai & menambahkan file...")
            engine.stageAll(repo, options.keepRemoteDeletions)
            if (skipSecrets.isNotEmpty()) {
                removeFromIndex(repo, skipSecrets)
            }
            if (plan.tooBig.isNotEmpty()) {
                removeFromIndex(repo, plan.tooBig.map { it.rel }.toSet())
            }

            val staged = engine.stagedSummary(repo)
            val fileCount = staged.changed

            if (plan.tooBig.isNotEmpty()) {
                reporter.warn("${plan.tooBig.size} file >100MB dilewati (batas GitHub).")
            }

            if (fileCount == 0) {
                reporter.ok("Tidak ada perubahan; repository sudah up-to-date.")
                return UploadOutcome(
                    project = plan.target.displayName,
                    repoUrl = "https://github.com/$login/${plan.repoName}",
                    upToDate = true,
                    seconds = secondsSince(started)
                )
            }

            if (options.dryRun) {
                reporter.info("[DRY-RUN] $fileCount file akan diupload ke $login/${plan.repoName} (${if (plan.isPublic) "public" else "private"}), branch ${plan.branch}. Contoh:")
                staged.examples.forEach { reporter.info("      $it") }
                if (fileCount > staged.examples.size) reporter.info("      ... dan ${fileCount - staged.examples.size} file lainnya")
                reporter.ok("Dry-run selesai. Tidak ada yang dikirim.")
                return UploadOutcome(
                    project = plan.target.displayName,
                    repoUrl = "https://github.com/$login/${plan.repoName}",
                    fileCount = fileCount,
                    dryRun = true,
                    skippedBig = plan.tooBig.size,
                    seconds = secondsSince(started)
                )
            }

            // --- commit (lokal saja) ---
            val msg = options.commitMessage?.takeIf { it.isNotBlank() }
                ?: if (plan.exists) "Update from Android ${timestamp()}" else "Initial commit from Android"
            val commitId = engine.commit(repo, msg, login, DEFAULT_EMAIL)
            reporter.ok("$fileCount file siap dikirim.")

            // --- repo baru dibuat tepat sebelum push ---
            if (!plan.exists) {
                reporter.info("Membuat repository baru (${if (plan.isPublic) "public" else "private"})...")
                val desc = options.description.ifBlank { DEFAULT_DESC }
                when (val r = client.createRepo(plan.repoName, desc, !options.isPublic)) {
                    is ApiResult.Ok -> reporter.ok("Repository dibuat.")
                    is ApiResult.Err -> throw UploadException(r.message)
                }
            } else if (options.description.isNotBlank()) {
                when (val r = client.updateDescription(login, plan.repoName, options.description)) {
                    is ApiResult.Ok -> Unit
                    is ApiResult.Err -> reporter.warn(r.message)
                }
            }

            // --- push ---
            engine.push(repo, plan.branch, token)
            val verified = engine.verifyRemote(repo, plan.branch, token, commitId)
            if (verified) {
                reporter.ok("Verifikasi: commit sudah ada di GitHub (${commitId.name.take(7)}).")
            } else {
                reporter.warn("Tidak bisa memverifikasi commit di server; cek manual di GitHub.")
            }

            val url = "https://github.com/$login/${plan.repoName}"
            reporter.ok("Selesai: $fileCount file, ${secondsSince(started)} detik.")
            return UploadOutcome(
                project = plan.target.displayName,
                repoUrl = url,
                fileCount = fileCount,
                skippedBig = plan.tooBig.size,
                seconds = secondsSince(started)
            )
        } catch (e: UploadException) {
            reporter.error(e.message ?: "Gagal upload.")
            return UploadOutcome(
                project = plan.target.displayName,
                seconds = secondsSince(started),
                error = e.message ?: "Gagal upload."
            )
        } catch (e: Exception) {
            val m = e.message ?: e.javaClass.simpleName
            reporter.error("Kesalahan tak terduga: $m")
            return UploadOutcome(
                project = plan.target.displayName,
                seconds = secondsSince(started),
                error = m
            )
        } finally {
            try {
                git?.close()
            } catch (ignored: Exception) {
            }
            plan.workspace?.deleteRecursively()
            plan.workspace = null
        }
    }

    // ------------------------------------------------------------- helpers --
    /** Setara: git rm -q --cached --ignore-unmatch -- FILE */
    private fun removeFromIndex(git: org.eclipse.jgit.api.Git, paths: Set<String>) {
        try {
            val cache = git.repository.readDirCache()
            val tracked = paths.filter { cache.getEntry(it) != null }
            if (tracked.isEmpty()) return
            val cmd = git.rm()
            cmd.setCached(true)
            tracked.forEach { cmd.addFilepattern(it) }
            cmd.call()
        } catch (e: Exception) {
            reporter.warn("Sebagian file gagal dikeluarkan dari index: ${e.message}")
        }
    }

    private fun validateBranch(branch: String) {
        val ok = branch.isNotBlank() && !branch.startsWith("-") && !branch.startsWith("/") &&
            !branch.endsWith("/") && !branch.endsWith(".lock") && !branch.contains("..") &&
            !branch.contains(" ") && !branch.contains("~") && !branch.contains("^") &&
            !branch.contains(":") && !branch.contains("?") && !branch.contains("*") &&
            !branch.contains("[") && !branch.contains("\\") && !branch.contains("//") &&
            branch != "HEAD" && branch.isNotEmpty()
        if (!ok) throw UploadException("Nama branch tidak valid: $branch")
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    private fun secondsSince(startMs: Long): Int = ((System.currentTimeMillis() - startMs) / 1000L).toInt()

    companion object {
        const val DEFAULT_EMAIL = "fadil111example@gmail.com"
        const val DEFAULT_DESC = "Uploaded from Android"
    }
}
