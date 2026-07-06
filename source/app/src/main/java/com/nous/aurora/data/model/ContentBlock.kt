package com.nous.aurora.data.model

sealed class ContentBlock {
    abstract val text: String

    data class Paragraph(override val text: String) : ContentBlock()
    data class Heading(override val text: String, val level: Int = 1) : ContentBlock()
    data class Link(override val text: String, val href: String) : ContentBlock()
    data class Image(
        override val text: String,
        val src: String,
        val imageData: ByteArray? = null
    ) : ContentBlock() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Image) return false
            return text == other.text && src == other.src
        }
        override fun hashCode(): Int = 31 * text.hashCode() + src.hashCode()
    }
    data class PageBreak(override val text: String = "") : ContentBlock()
    data class Separator(override val text: String = "") : ContentBlock()
    data class Table(
        override val text: String,
        val headers: List<String>,
        val rows: List<List<String>>
    ) : ContentBlock()
    data class Formula(override val text: String, val display: Boolean = false) : ContentBlock()
    data class Code(override val text: String, val language: String = "") : ContentBlock()
}
