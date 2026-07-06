package com.nous.aurora.data.model

data class BookAnnotation(
    val id: Long = 0,
    val bookId: Long,
    val paragraphIndex: Int,
    val text: String,
    val createdTime: Long = System.currentTimeMillis(),
    val modifiedTime: Long = System.currentTimeMillis(),
    /** JSON-serialized Readium Locator for EPUB annotations */
    val locatorJson: String = ""
)
