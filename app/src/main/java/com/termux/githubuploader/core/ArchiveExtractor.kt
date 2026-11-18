package com.termux.githubuploader.core

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Pengganti perintah `unzip`, `tar -xf`, dan `7z x` di upload.sh.
 * Semua ekstraksi dibatasi ke folder tujuan (anti path-traversal).
 */
object ArchiveExtractor {

    private const val BUFFER = 64 * 1024
    private const val MAX_ENTRIES = 200_000

    fun unzip(context: Context, uri: Uri, dest: File, reporter: Reporter) {
        reporter.info("Mengekstrak zip...")
        val count = openInput(context, uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw, BUFFER)).use { zip ->
                var n = 0
                var entry = zip.nextEntry
                while (entry != null) {
                    if (n++ > MAX_ENTRIES) throw UploadException("Arsip terlalu banyak file (maks $MAX_ENTRIES).")
                    val out = safeChild(dest, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o -> zip.copyTo(o, BUFFER) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                n
            }
        } ?: throw UploadException("Zip tidak bisa dibaca / berpassword / rusak.")

        if (count == 0) throw UploadException("Zip kosong atau berpassword (isi tidak bisa dibaca).")
    }

    fun untar(context: Context, uri: Uri, dest: File, reporter: Reporter) {
        val name = uri.toString().lowercase(Locale.ROOT)
        reporter.info("Mengekstrak tar...")
        val raw = openInput(context, uri) ?: throw UploadException("Arsip tidak bisa dibaca.")
        val stream: InputStream = BufferedInputStream(raw, BUFFER)
        val tar: InputStream = when {
            name.contains(".gz") || name.contains(".tgz") -> GzipCompressorInputStream(stream, true)
            name.contains(".bz2") || name.contains(".tbz2") -> BZip2CompressorInputStream(stream, true)
            name.contains(".xz") || name.contains(".txz") -> XZCompressorInputStream(stream, true)
            else -> stream
        }
        tar.use { input ->
            TarArchiveInputStream(input).use { tin ->
                var n = 0
                while (true) {
                    val entry = tin.nextEntry ?: break
                    if (n++ > MAX_ENTRIES) throw UploadException("Arsip terlalu banyak file (maks $MAX_ENTRIES).")
                    val out = safeChild(dest, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else if (entry.isSymbolicLink || entry.isLink) {
                        // Link tidak diikuti: isi file tetap aman tanpa symlink yang bisa keluar folder.
                        continue
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o -> tin.copyTo(o, BUFFER) }
                        if (entry.modTime != null) out.setLastModified(entry.modTime.time)
                    }
                }
            }
        }
    }

    fun sevenZip(context: Context, uri: Uri, dest: File, reporter: Reporter) {
        reporter.info("Mengekstrak 7z...")
        // Commons Compress butuh file acak -> salin arsip ke cache dulu.
        val temp = File(context.cacheDir, "sevenz-" + System.currentTimeMillis() + ".7z")
        try {
            openInput(context, uri)?.use { input ->
                temp.outputStream().use { out -> input.copyTo(out, BUFFER) }
            } ?: throw UploadException("Arsip 7z tidak bisa dibaca.")

            SevenZFile(temp).use { sz ->
                var n = 0
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val entry = sz.nextEntry ?: break
                    if (n++ > MAX_ENTRIES) throw UploadException("Arsip terlalu banyak file (maks $MAX_ENTRIES).")
                    val out = safeChild(dest, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            while (true) {
                                val read = sz.read(buffer)
                                if (read < 0) break
                                o.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
        } finally {
            temp.delete()
        }
    }

    /** Mencegah entri "../evil" menulis di luar folder tujuan. */
    private fun safeChild(dest: File, entryName: String): File {
        val clean = entryName.replace('\\', '/').trimStart('/')
        val out = File(dest, clean)
        val base = dest.canonicalFile
        val target = out.canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separator)) {
            throw UploadException("Arsip berisi path tidak aman: $entryName")
        }
        return out
    }

    private fun openInput(context: Context, uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(uri)
    } catch (e: Exception) {
        null
    }
}
