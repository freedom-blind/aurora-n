package com.nous.aurora.data.model

import android.net.Uri

enum class BookFormat {
    EPUB, PDF, MOBI, AZW, AZW3, TXT, MARKDOWN, FB2, UNKNOWN;

    companion object {
        fun fromExtension(ext: String): BookFormat = when (ext.lowercase()) {
            "epub" -> EPUB
            "pdf" -> PDF
            "mobi" -> MOBI
            "azw" -> AZW
            "azw3" -> AZW3
            "txt" -> TXT
            "md", "markdown" -> MARKDOWN
            "fb2" -> FB2
            else -> UNKNOWN
        }
    }

    /** Whether this format is supported by Readium Navigator */
    val isReadiumSupported: Boolean
        get() = this == EPUB || this == PDF
}

data class Book(
    val id: Long = 0,
    val filePath: String,
    val title: String,
    val author: String = "",
    val publisher: String = "",
    val year: String = "",
    val language: String = "",
    val description: String = "",
    val coverPath: String = "",
    val format: BookFormat,
    val lastParagraphIndex: Int = 0,
    val totalParagraphs: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0,
    val fileModifiedAt: Long = 0,
    val isFavorite: Boolean = false,
    /** JSON-serialized Readium Locator for EPUB/PDF reading position */
    val locatorJson: String = ""
)
