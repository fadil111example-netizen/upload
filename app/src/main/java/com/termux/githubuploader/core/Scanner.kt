package com.termux.githubuploader.core

import java.io.File
import java.util.Locale

/**
 * Pengganti:
 *  - find + prune (node_modules, __pycache__, .gradle)
 *  - find -size +100M
 *  - grep -IlE '<pola rahasia>' dan daftar file .env / id_rsa / *.pem
 *  - git add -A (plus info/exclude untuk file yang dilewati)
 */
class Scanner(
    private val root: File,
    private val includeIgnored: Boolean,
    private val maxFileMb: Int = DEFAULT_MAX_FILE_MB,
    /** true = repo public, jadi nama file kredensial (.env, id_rsa, *.pem) ikut dicek. */
    private val checkCredentialNames: Boolean = false
) {

    data class Item(
        val file: File,
        val rel: String,
        val size: Long
    )

    data class SecretHit(val rel: String, val reason: String)

    data class Report(
        val files: List<Item>,
        val tooBig: List<Item>,
        val secrets: List<SecretHit>,
        val excludedDirs: List<String>,
        val totalBytes: Long,
        val nestedGit: Int,
        /** Baris untuk .git/info/exclude (setara info/exclude di skrip). */
        val ignorePatterns: List<String>
    )

    fun scan(): Report {
        val files = ArrayList<Item>()
        val tooBig = ArrayList<Item>()
        val secrets = ArrayList<SecretHit>()
        val excludedDirs = ArrayList<String>()
        var total = 0L
        var nestedGit = 0

        val hasGitignore = File(root, ".gitignore").isFile
        val prune = !includeIgnored && !hasGitignore
        val ignorePatterns = ArrayList<String>()
        if (prune) ignorePatterns += AUTO_PRUNE_PATTERNS

        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                val name = child.name
                val rel = relative(child)
                if (name == ".git") {
                    // Setara: find "$ROOT" -mindepth 2 -name .git
                    if (dir != root) nestedGit++
                    continue
                }
                if (child.isDirectory) {
                    if (prune && name in AUTO_PRUNE && child != root) {
                        excludedDirs.add(rel)
                        ignorePatterns += "/${escapeIgnore(rel)}/"
                        continue
                    }
                    stack.addLast(child)
                    continue
                }
                if (!child.isFile) continue
                // Setara baris info/exclude di skrip: *.pyc, .DS_Store, Thumbs.db.
                if (prune && (name == ".DS_Store" || name == "Thumbs.db" || name.endsWith(".pyc"))) {
                    ignorePatterns += "/${escapeIgnore(rel)}"
                    continue
                }
                val size = child.length()
                if (size > maxFileMb.toLong() * 1024 * 1024) {
                    tooBig.add(Item(child, rel, size))
                    ignorePatterns += "/${escapeIgnore(rel)}"
                    continue
                }
                val item = Item(child, rel, size)
                files.add(item)
                total += size
                secretReason(child, rel)?.let { secrets.add(SecretHit(rel, it)) }
            }
        }
        files.sortBy { it.rel.lowercase(Locale.ROOT) }
        return Report(files, tooBig, secrets, excludedDirs, total, nestedGit, ignorePatterns)
    }

    private fun relative(f: File): String = f.path.removePrefix(root.path).trimStart(File.separatorChar).replace(File.separatorChar, '/')

    // ------------------------------------------------------------- SECRETS --
    private fun secretReason(file: File, rel: String): String? {
        val base = file.name.lowercase(Locale.ROOT)
        if (checkCredentialNames && PRIVATE_NAME.matches(base)) {
            return "nama file kredensial: $rel"
        }
        if (file.length() > MAX_SCAN_BYTES) return null
        val text = try {
            val bytes = file.inputStream().use { input ->
                val buffer = ByteArray(MAX_SCAN_BYTES.toInt())
                var total = 0
                var read = input.read(buffer)
                while (read > 0 && total < buffer.size) {
                    total += read
                    read = input.read(buffer, total, buffer.size - total)
                }
                buffer.copyOf(total)
            }
            if (bytes.contains(0.toByte())) return null
            String(bytes, Charsets.ISO_8859_1)
        } catch (e: Exception) {
            return null
        }
        for (p in SECRET_PATTERNS) {
            if (p.containsMatchIn(text)) return "isi mirip rahasia: ${p.pattern.take(40)}"
        }
        return null
    }

    companion object {
        const val DEFAULT_MAX_FILE_MB = 100
        private const val MAX_SCAN_BYTES = 2L * 1024 * 1024

        val AUTO_PRUNE = setOf("node_modules", "__pycache__", ".gradle")

        /** Daftar yang ditulis ke info/exclude bila project tidak punya .gitignore. */
        private val AUTO_PRUNE_PATTERNS = listOf("node_modules/", "__pycache__/", "*.pyc", ".gradle/", ".DS_Store", "Thumbs.db")

        /** Sama seperti excl_add() di skrip: escape karakter khusus gitignore. */
        fun escapeIgnore(rel: String): String {
            val special = "[]*?!#\\ "
            val sb = StringBuilder()
            for (c in rel) {
                if (special.indexOf(c) >= 0) sb.append('\\')
                sb.append(c)
            }
            return sb.toString()
        }

        /** Setara pola grep -E di skrip (token, key, private key). */
        val SECRET_PATTERNS: List<Regex> = listOf(
            Regex("ghp_[A-Za-z0-9]{36}"),
            Regex("github_pat_[A-Za-z0-9_]{50,}"),
            Regex("gh[ousr]_[A-Za-z0-9]{36}"),
            Regex("AKIA[0-9A-Z]{16}"),
            Regex("AIza[0-9A-Za-z_-]{35}"),
            Regex("xox[baprs]-[0-9A-Za-z-]{10,}"),
            Regex("sk_live_[0-9A-Za-z]{20,}"),
            Regex("-----BEGIN ([A-Z]+ )?PRIVATE KEY-----")
        )

        /** File kredensial yang hanya dicek bila repo akan dibuat public. */
        val PRIVATE_NAME = Regex("(\\.env(\\..*)?)|(id_rsa)|(id_ed25519)|([^/]*\\.(pem|key|jks|keystore))")
    }
}
