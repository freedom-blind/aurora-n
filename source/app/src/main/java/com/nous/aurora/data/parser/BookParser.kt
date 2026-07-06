package com.nous.aurora.data.parser

import com.nous.aurora.data.model.ContentBlock

interface BookParser {
    fun parse(filePath: String): ParseResult
    fun getCoverImage(filePath: String): ByteArray? = null
    val supportedExtensions: List<String>
}

data class ParseResult(
    val blocks: List<ContentBlock>,
    val metadata: BookMetadata,
    val toc: List<TocEntry> = emptyList(),
    val linkMap: Map<String, Int> = emptyMap()  // href → paragraphIndex for internal links
)

data class BookMetadata(
    val title: String,
    val author: String = "",
    val publisher: String = "",
    val year: String = "",
    val language: String = "",
    val description: String = "",
    val encoding: String = ""  // detected encoding for TXT files
)

data class TocEntry(
    val title: String,
    val paragraphIndex: Int,
    val level: Int = 0,
    val children: List<TocEntry> = emptyList()
)
