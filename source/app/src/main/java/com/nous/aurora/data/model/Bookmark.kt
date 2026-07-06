package com.nous.aurora.data.model

data class Bookmark(
    val id: Long = 0,
    val bookId: Long,
    val paragraphIndex: Int,
    val createdTime: Long = System.currentTimeMillis(),
    /** JSON-serialized Readium Locator for EPUB bookmarks */
    val locatorJson: String = ""
)
