package com.nous.aurora.ui.reader

import com.nous.aurora.data.model.Bookmark
import com.nous.aurora.data.model.BookAnnotation

/**
 * Contract between UniversalReaderActivity and its inner reading fragments.
 * All location references are serialized as JSON strings for uniform handling.
 */
interface ReaderFragmentContract {
    /** Get current reading location as JSON string */
    fun getCurrentLocation(): String

    /** Navigate to a serialized location */
    fun goToLocation(locationJson: String)

    /** Navigate by progress percentage (0-100) */
    fun goToProgress(percent: Int)

    /** Get current progress as percentage (0-100) */
    fun getProgressPercent(): Int

    /** Get table of contents as flat list of (title, locationJson, depth) */
    fun getTableOfContents(): List<TocItem>

    /** Get table of contents as a tree for expand/collapse display */
    fun getTableOfContentsTree(): List<TocTreeItem> {
        // Default: convert flat TOC to tree
        return buildTocTreeFromFlat(getTableOfContents())
    }

    /** Search document, returning list of (preview, locationJson) */
    suspend fun search(query: String): List<SearchResult>

    /** Add a bookmark at current location */
    fun addBookmark()

    /** Get all bookmarks for this book */
    suspend fun getBookmarks(): List<Bookmark>

    /** Add an annotation at current location with given text */
    fun addAnnotation(text: String)

    /** Get all annotations for this book */
    suspend fun getAnnotations(): List<BookAnnotation>

    /**
     * Go back from a footnote / internal link jump.
     * Returns true if there was a footnote to go back to.
     */
    fun goBackFromFootnote(): Boolean = false

    data class TocItem(val title: String, val locationJson: String, val depth: Int = 0)

    /** Tree-structured TOC item for expand/collapse display */
    data class TocTreeItem(
        val title: String,
        val locationJson: String,
        val depth: Int = 0,
        val children: List<TocTreeItem> = emptyList()
    )

    data class SearchResult(val preview: String, val locationJson: String, val title: String? = null)

    companion object {
        /**
         * Build a TOC tree from a flat list of TocItems.
         * Items with higher depth become children of the nearest preceding item with lower depth.
         */
        fun buildTocTreeFromFlat(flatItems: List<TocItem>): List<TocTreeItem> {
            if (flatItems.isEmpty()) return emptyList()

            // Use mutable builders internally
            data class Builder(
                val title: String,
                val locationJson: String,
                val depth: Int,
                val children: MutableList<Builder> = mutableListOf()
            )

            val builders = flatItems.map { Builder(it.title, it.locationJson, it.depth) }
            val roots = mutableListOf<Builder>()
            val stack = ArrayDeque<Builder>()

            for (builder in builders) {
                // Pop stack until we find a parent with strictly lower depth
                while (stack.isNotEmpty() && stack.last().depth >= builder.depth) {
                    stack.removeLast()
                }

                if (stack.isEmpty()) {
                    roots.add(builder)
                } else {
                    stack.last().children.add(builder)
                }
                stack.addLast(builder)
            }

            fun Builder.toFinal(): TocTreeItem =
                TocTreeItem(title, locationJson, depth, children.map { it.toFinal() })

            return roots.map { it.toFinal() }
        }
    }
}
