package com.termux.githubuploader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.termux.githubuploader.core.AccountInfo
import com.termux.githubuploader.core.ApiResult
import com.termux.githubuploader.core.GitHubClient
import com.termux.githubuploader.core.LogLevel
import com.termux.githubuploader.core.Names
import com.termux.githubuploader.core.ProjectLoader
import com.termux.githubuploader.core.Reporter
import com.termux.githubuploader.core.Sizes
import com.termux.githubuploader.core.Target
import com.termux.githubuploader.core.TargetKind
import com.termux.githubuploader.core.TokenStore
import com.termux.githubuploader.core.UploadEngine
import com.termux.githubuploader.core.UploadOptions
import com.termux.githubuploader.core.UploadOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogLine(val level: LogLevel, val text: String, val time: String)

data class Toast(val id: Long, val message: String)

data class UiState(
    val account: AccountInfo? = null,
    val tokenPresent: Boolean = false,
    val checkingToken: Boolean = false,
    val targets: List<Target> = emptyList(),
    val options: UploadOptions = UploadOptions(),
    val running: Boolean = false,
    val progress: String? = null,
    val log: List<LogLine> = emptyList(),
    val outcomes: List<UploadOutcome> = emptyList(),
    val pendingSecrets: PendingSecrets? = null,
    val tokenDialogOpen: Boolean = false,
    val toast: Toast? = null,
    val lastSummary: String? = null
)

data class PendingSecrets(
    val repoName: String,
    val files: List<String>,
    val warnings: List<String>
)

class UploadViewModel(app: Application) : AndroidViewModel(app) {

    private val tokens = TokenStore(app)
    private val loader = ProjectLoader(app)

    private val _state = MutableStateFlow(UiState(tokenPresent = tokens.token() != null))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null
    private var idCounter = 0L

    init {
        // Bersihkan salinan project yang tertinggal bila app pernah ditutup paksa.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                loader.cleanupStaleSessions()
            } catch (ignored: Exception) {
            }
        }
        if (tokens.token() != null) refreshAccount(silent = true)
    }

    // ------------------------------------------------------------------ TOKEN --
    fun refreshAccount(silent: Boolean = false) {
        val token = tokens.token()
        if (token == null) {
            _state.update { it.copy(tokenPresent = false, account = null) }
            if (!silent) toast("Belum ada token. Buka menu Token untuk menyimpannya.")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(checkingToken = true, tokenPresent = true) }
            val result = withContext(Dispatchers.IO) {
                val client = GitHubClient(token)
                client.checkAccount() to client
            }
            _state.update { it.copy(checkingToken = false) }
            when (val r = result.first) {
                is ApiResult.Ok -> _state.update {
                    it.copy(account = r.value)
                }
                is ApiResult.Err -> {
                    _state.update { it.copy(account = null) }
                    val msg = if (r.code == 401) "Token lama tidak valid / kedaluwarsa. Simpan token baru." else r.message
                    writeLog(LogLevel.ERROR, msg)
                    if (!silent) toast(msg)
                }
            }
        }
    }

    fun saveToken(raw: String, onDone: (Boolean) -> Unit) {
        val token = raw.trim().replace(" ", "").replace("\n", "")
        if (token.isEmpty()) {
            toast("Token kosong.")
            onDone(false)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(checkingToken = true) }
            val client = GitHubClient(token)
            val result = withContext(Dispatchers.IO) { client.checkAccount() }
            _state.update { it.copy(checkingToken = false) }
            when (val r = result) {
                is ApiResult.Ok -> {
                    tokens.save(token, r.value.login)
                    _state.update { it.copy(account = r.value, tokenPresent = true) }
                    writeLog(LogLevel.OK, "Token valid. Login sebagai ${r.value.login}.")
                    if (r.value.missingRepoScope) {
                        writeLog(LogLevel.WARN, "Token classic ini tanpa scope 'repo' -> upload bisa gagal.")
                    }
                    toast("Token disimpan (cukup sekali).")
                    _state.update { it.copy(tokenDialogOpen = false) }
                    onDone(true)
                }
                is ApiResult.Err -> {
                    val msg = when (r.code) {
                        401 -> "Token ditolak GitHub (salah / expired)."
                        0 -> "Tidak ada koneksi internet."
                        else -> "GitHub membalas HTTP ${r.code}: ${r.message}"
                    }
                    toast(msg)
                    onDone(false)
                }
            }
        }
    }

    fun logout() {
        tokens.clear()
        _state.update { it.copy(account = null, tokenPresent = false, outcomes = emptyList()) }
        writeLog(LogLevel.INFO, "Token tersimpan sudah dihapus.")
        toast("Token dihapus.")
    }

    // ---------------------------------------------------------------- TARGETS --
    fun addTargets(uris: List<Uri>, kind: TargetKind) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                val known = _state.value.targets.map { it.uri }.toSet()
                uris.distinct().filterNot { it in known }.map { uri ->
                    val name = loader.queryName(uri)
                    Target(uri, name, kind)
                }
            }
            if (added.isEmpty()) {
                toast("Project itu sudah ada di daftar.")
                return@launch
            }
            _state.update { current ->
                val merged = (current.targets + added).distinctBy { it.uri }
                current.copy(targets = merged)
            }
            val existing = _state.value.targets.size
            if (existing > 1 && !_state.value.options.repoName.isNullOrBlank()) {
                _state.update { it.copy(options = it.options.copy(repoName = null)) }
                toast("Nama repo kustom dihapus karena lebih dari satu project.")
            }
        }
    }

    fun removeTarget(uri: Uri) {
        _state.update { s -> s.copy(targets = s.targets.filterNot { it.uri == uri }) }
    }

    // ---------------------------------------------------------------- OPTIONS --
    fun updateOptions(transform: (UploadOptions) -> UploadOptions) {
        _state.update { it.copy(options = transform(it.options)) }
    }

    fun openTokenDialog() {
        _state.update { it.copy(tokenDialogOpen = true) }
    }

    fun closeTokenDialog() {
        _state.update { it.copy(tokenDialogOpen = false) }
    }

    // -------------------------------------------------------------------- RUN --
    fun start() {
        val s = _state.value
        if (s.running) return
        val token = tokens.token()
        if (token == null) {
            toast("Simpan token GitHub dulu (menu Token).")
            return
        }
        if (s.targets.isEmpty()) {
            toast("Pilih minimal satu folder atau arsip.")
            return
        }
        idCounter++
        _state.update {
            it.copy(
                running = true,
                log = emptyList(),
                outcomes = emptyList(),
                lastSummary = null,
                toast = Toast(idCounter, "Mulai upload...")
            )
        }
        job = viewModelScope.launch {
            var login = s.account?.login
            val client = GitHubClient(token)
            if (login == null) {
                when (val r = withContext(Dispatchers.IO) { client.checkAccount() }) {
                    is ApiResult.Ok -> {
                        login = r.value.login
                        _state.update { it.copy(account = r.value) }
                    }
                    is ApiResult.Err -> {
                        writeLog(LogLevel.ERROR, r.message)
                        _state.update { it.copy(running = false) }
                        toast(r.message)
                        return@launch
                    }
                }
            }
            val user = login ?: run {
                _state.update { it.copy(running = false) }
                toast("Login GitHub tidak diketahui.")
                return@launch
            }

            val engine = UploadEngine(getApplication(), uiReporter())
            val outcomes = ArrayList<UploadOutcome>()
            val total = s.targets.size

            for ((index, target) in s.targets.withIndex()) {
                if (!isActive) break
                writeLog(LogLevel.INFO, "======== [$total] ${index + 1}/$total  ${target.displayName} ========")
                _state.update { it.copy(progress = "Menyiapkan ${target.displayName}...") }

                val plan = try {
                    withContext(Dispatchers.IO) { engine.plan(target, s.options, user, client) }
                } catch (e: Exception) {
                    val msg = e.message ?: "Gagal membaca project."
                    writeLog(LogLevel.ERROR, msg)
                    outcomes.add(UploadOutcome(project = target.displayName, error = msg))
                    _state.update { it.copy(outcomes = outcomes.toList()) }
                    continue
                }

                // Konfirmasi file sensitif (sama seperti pertanyaan di skrip).
                val skip = HashSet<String>()
                if (plan.secrets.isNotEmpty() && !s.options.autoConfirm) {
                    val answer = askSecrets(plan)
                    if (answer == null) {
                        engine.release(plan)
                        writeLog(LogLevel.WARN, "Dilewati oleh pengguna: ${target.displayName}")
                        outcomes.add(UploadOutcome(project = target.displayName, error = "Dibatalkan oleh pengguna."))
                        _state.update { it.copy(outcomes = outcomes.toList()) }
                        continue
                    }
                    if (answer) skip.addAll(plan.secrets.map { it.rel })
                } else if (plan.secrets.isNotEmpty()) {
                    skip.addAll(plan.secrets.map { it.rel })
                    writeLog(LogLevel.WARN, "Mode otomatis: ${plan.secrets.size} file sensitif dilewati.")
                }

                _state.update {
                    it.copy(progress = "Mengupload ${target.displayName} (${plan.files.size} file, ${Sizes.human(plan.totalBytes)})")
                }
                val outcome = withContext(Dispatchers.IO) {
                    engine.upload(plan, s.options, token, user, client, skip)
                }
                outcomes.add(outcome)
                _state.update { it.copy(outcomes = outcomes.toList(), progress = null) }
            }

            val ok = outcomes.count { it.success }
            val fail = outcomes.size - ok
            val summary = if (total > 1) "Ringkasan: $ok berhasil, $fail gagal." else null
            if (!s.options.dryRun) {
                writeLog(if (fail == 0) LogLevel.OK else LogLevel.WARN, summary ?: (outcomes.firstOrNull()?.let {
                    if (it.success) "Upload selesai." else "Upload gagal."
                } ?: "Selesai."))
            }
            _state.update { it.copy(running = false, progress = null, lastSummary = summary) }
        }
    }

    /**
     * Menunggu jawaban pengguna seperti `confirm()` di skrip.
     * true = lewati file sensitif, false = tetap kirim, null = dibatalkan.
     */
    private suspend fun askSecrets(plan: UploadEngine.Plan): Boolean? {
        val deferred = CompletableDeferred<Boolean?>()
        pendingAnswer = deferred
        _state.update {
            it.copy(
                pendingSecrets = PendingSecrets(
                    repoName = plan.repoName,
                    files = plan.secrets.map { hit -> hit.rel },
                    warnings = plan.secrets.map { it.reason }.distinct()
                )
            )
        }
        val answer = deferred.await()
        _state.update { it.copy(pendingSecrets = null) }
        return answer
    }

    private var pendingAnswer: CompletableDeferred<Boolean?>? = null

    fun answerSecrets(skip: Boolean) {
        pendingAnswer?.complete(skip)
        pendingAnswer = null
    }

    fun cancelSecrets() {
        pendingAnswer?.complete(null)
        pendingAnswer = null
    }

    fun cancelUpload() {
        pendingAnswer?.complete(null)
        pendingAnswer = null
        job?.cancel()
        _state.update { it.copy(running = false, progress = null, pendingSecrets = null) }
        writeLog(LogLevel.WARN, "Dibatalkan oleh pengguna.")
    }

    // ------------------------------------------------------------------- MISC --
    fun toast(message: String) {
        idCounter++
        _state.update { it.copy(toast = Toast(idCounter, message)) }
    }

    fun toastShown() {
        _state.update { it.copy(toast = null) }
    }

    fun clearLog() {
        _state.update { it.copy(log = emptyList(), outcomes = emptyList(), lastSummary = null) }
    }

    fun readBundledScript(): String = try {
        getApplication<Application>().assets.open("upload.sh")
            .bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        "upload.sh tidak ditemukan di dalam aplikasi."
    }

    private fun writeLog(level: LogLevel, text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update { it.copy(log = (it.log + LogLine(level, text, time)).takeLast(600)) }
    }

    private fun uiReporter(): Reporter = object : Reporter {
        override fun info(message: String) = writeLog(LogLevel.INFO, message)
        override fun ok(message: String) = writeLog(LogLevel.OK, message)
        override fun warn(message: String) = writeLog(LogLevel.WARN, message)
        override fun error(message: String) = writeLog(LogLevel.ERROR, message)
        override fun progress(text: String?) = _state.update { it.copy(progress = text) }
    }

    fun suggestRepoName(): String? {
        val first = _state.value.targets.firstOrNull() ?: return null
        return if (first.kind == TargetKind.ARCHIVE) Names.archiveBaseName(first.displayName)
        else Names.repoName(first.displayName)
    }
}
