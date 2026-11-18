package com.termux.githubuploader.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * Mengubah pilihan pengguna (folder DocumentTree atau arsip) menjadi folder biasa
 * di penyimpanan privat aplikasi, sehingga mesin Git bisa membacanya.
 *
 * Di skrip pekerjaan ini dilakukan oleh: cp -r / unzip / tar / 7z.
 */
class ProjectLoader(private val context: Context) {

    data class Loaded(
        val root: File,
        val displayName: String,
        val fromArchive: Boolean,
        val skipped: List<String> = emptyList()
    )

    private val scratch = File(context.filesDir, "work").apply { mkdirs() }

    /** Folder kerja baru; dibersihkan oleh pemanggil. */
    fun newWorkspace(): File = File(scratch, "job-" + System.currentTimeMillis()).apply {
        deleteRecursively()
        mkdirs()
    }

    /** Hapus sisa workspace dari sesi yang terhenti (app ditutup / crash). */
    fun cleanupStaleSessions() {
        scratch.listFiles()?.forEach { dir ->
            try {
                dir.deleteRecursively()
            } catch (ignored: Exception) {
            }
        }
    }

    // ------------------------------------------------------------- ENTRY ---
    fun load(target: Target, workspace: File, reporter: Reporter): Loaded {
        return when (target.kind) {
            TargetKind.FOLDER -> loadFolder(target, workspace, reporter)
            TargetKind.ARCHIVE -> loadArchive(target, workspace, reporter)
        }
    }

    // ------------------------------------------------------------ FOLDER ---
    private fun loadFolder(target: Target, workspace: File, reporter: Reporter): Loaded {
        val dest = File(workspace, "project")
        reporter.info("Mode: FOLDER -> ${target.displayName}")
        val tree = DocumentFile.fromTreeUri(context, target.uri)
            ?: throw UploadException("Folder tidak bisa dibuka. Pilih ulang foldernya.")
        if (!tree.canRead()) throw UploadException("Tidak punya izin membaca folder: ${target.displayName}")

        val skipped = ArrayList<String>()
        copyTree(tree, dest, skipped)
        return Loaded(dest, target.displayName, fromArchive = false, skipped = skipped)
    }

    private fun copyTree(dir: DocumentFile, dest: File, skipped: MutableList<String>) {
        dest.mkdirs()
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            // Jangan salin metadata git (bisa berisi path/credential mesin lain).
            if (name == ".git") continue
            val out = File(dest, name)
            if (child.isDirectory) {
                copyTree(child, out, skipped)
            } else {
                try {
                    openStream(child.uri)?.use { input ->
                        out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    } ?: skipped.add(name)
                } catch (e: IOException) {
                    skipped.add(name)
                }
                val modified = child.lastModified()
                if (modified > 0) out.setLastModified(modified)
            }
        }
    }

    // ----------------------------------------------------------- ARCHIVE ---
    private fun loadArchive(target: Target, workspace: File, reporter: Reporter): Loaded {
        val extractDir = File(workspace, "extract")
        extractDir.mkdirs()
        reporter.info("Mode: ARSIP -> ${target.displayName}")

        val name = target.displayName
        val lower = name.lowercase(Locale.ROOT)
        when {
            lower.endsWith(".zip") -> ArchiveExtractor.unzip(context, target.uri, extractDir, reporter)
            lower.endsWith(".7z") -> ArchiveExtractor.sevenZip(context, target.uri, extractDir, reporter)
            Names.isArchive(lower) -> ArchiveExtractor.untar(context, target.uri, extractDir, reporter)
            else -> throw UploadException("Format tidak didukung: $name")
        }

        File(extractDir, "__MACOSX").deleteRecursively()
        extractDir.walkTopDown().filter { it.isFile && it.name == ".DS_Store" }.forEach { it.delete() }

        // Sama dengan logika "satu folder root tunggal" di skrip.
        val tops = extractDir.listFiles()?.filter { !it.name.startsWith(".") || it.name == ".gitignore" } ?: emptyList()
        val single = tops.size == 1 && tops[0].isDirectory
        return if (single) {
            val root = tops[0]
            reporter.ok("Folder root di dalam arsip: ${root.name} (jadi nama repository)")
            Loaded(root, root.name, fromArchive = true)
        } else {
            val base = Names.archiveBaseName(name)
            reporter.ok("Arsip tanpa folder root tunggal, nama repo dari nama arsip: $base")
            Loaded(extractDir, base, fromArchive = true)
        }
    }

    // ------------------------------------------------------------ HELPER ---
    fun queryName(uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getString(0)
                        if (!n.isNullOrBlank()) return n
                    }
                }
        } catch (ignored: Exception) {
        }
        return try {
            DocumentsContract.getDocumentId(uri).substringAfterLast('/').substringAfterLast(':')
        } catch (e: Exception) {
            uri.lastPathSegment ?: "project"
        }
    }

    fun isDirectory(uri: Uri): Boolean = DocumentFile.fromSingleUri(context, uri)?.isDirectory == true

    private fun openStream(uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(uri)
    } catch (e: Exception) {
        null
    }
}
