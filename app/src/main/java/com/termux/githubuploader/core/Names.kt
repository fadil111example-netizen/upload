package com.termux.githubuploader.core

import java.util.Locale

object Names {

    /**
     * Sama dengan sanitize_name() di upload.sh:
     * hanya [A-Za-z0-9._-], tanda hubung dirapikan, maksimal 100 karakter.
     */
    fun repoName(input: String): String {
        var n = input.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .replace(Regex("-{2,}"), "-")
            .replace(Regex("^[-.]+"), "")
            .replace(Regex("-+$"), "")
        if (n.endsWith(".git")) n = n.dropLast(4)
        if (n.length > 100) n = n.take(100)
        if (n.isEmpty()) n = "project"
        return n
    }

    /** Sama dengan logika $base di extract_archive(): buang ekstensi arsip. */
    fun archiveBaseName(fileName: String): String {
        val lower = fileName.lowercase(Locale.ROOT)
        val suffixes = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".txz", ".tar", ".zip", ".7z")
        for (s in suffixes) {
            if (lower.endsWith(s)) return fileName.dropLast(s.length).ifEmpty { "project" }
        }
        return fileName.substringBeforeLast('.', fileName).ifEmpty { "project" }
    }

    fun isArchive(fileName: String): Boolean {
        val lower = fileName.lowercase(Locale.ROOT)
        return lower.endsWith(".zip") || lower.endsWith(".tar") || lower.endsWith(".tar.gz") ||
            lower.endsWith(".tgz") || lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") ||
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") || lower.endsWith(".7z")
    }
}

object Sizes {
    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}
