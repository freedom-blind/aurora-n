package com.nous.aurora.ui.bookshelf

import java.io.File

data class FileEntry(
    val file: File,
    val isDirectory: Boolean,
    val isSupported: Boolean
) {
    val name: String get() = file.name
    val path: String get() = file.absolutePath

    companion object {
        private val SUPPORTED = setOf("epub", "pdf", "mobi", "azw", "azw3", "txt", "md", "markdown", "fb2")

        fun isSupportedFile(file: File): Boolean =
            file.isFile && file.extension.lowercase() in SUPPORTED

        fun fromFile(file: File): FileEntry = FileEntry(
            file = file,
            isDirectory = file.isDirectory,
            isSupported = isSupportedFile(file)
        )
    }
}
