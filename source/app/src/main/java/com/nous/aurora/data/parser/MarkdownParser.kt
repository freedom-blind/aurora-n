package com.nous.aurora.data.parser

import com.nous.aurora.data.model.ContentBlock
import org.commonmark.Extension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import java.io.File

/**
 * Industrial-grade Markdown parser using CommonMark Java.
 * Replaces the custom regex-based parser with a proper AST-based implementation.
 */
class MarkdownParser : BookParser {

    override val supportedExtensions = listOf("md", "markdown")

    private val commonmarkParser: org.commonmark.parser.Parser = org.commonmark.parser.Parser.builder()
        .extensions(listOf<Extension>(TablesExtension.create()))
        .build()

    override fun parse(filePath: String): ParseResult {
        val file = File(filePath)
        val source = file.readText(Charsets.UTF_8)
        val document = commonmarkParser.parse(source)

        val blocks = mutableListOf<ContentBlock>()
        val toc = mutableListOf<TocEntry>()

        var node: Node? = document.firstChild
        while (node != null) {
            when (node) {
                is Heading -> {
                    val text = extractText(node)
                    val level = node.level
                    blocks.add(ContentBlock.Heading(text, level))
                    toc.add(TocEntry(text, blocks.size - 1, level))
                }
                is FencedCodeBlock -> {
                    val code = node.literal ?: ""
                    val lang = node.info ?: ""
                    blocks.add(ContentBlock.Code(code, lang))
                }
                is Paragraph -> {
                    blocks.addAll(parseParagraph(node))
                }
                is BlockQuote -> {
                    val text = extractText(node)
                    if (text.isNotBlank()) {
                        blocks.add(ContentBlock.Paragraph(text))
                    }
                }
                is TableBlock -> {
                    blocks.add(parseTable(node))
                }
                is BulletList, is OrderedList -> {
                    var item = node.firstChild
                    while (item != null) {
                        if (item is ListItem) {
                            val text = extractText(item)
                            if (text.isNotBlank()) {
                                blocks.add(ContentBlock.Paragraph(text))
                            }
                        }
                        item = item.next
                    }
                }
                is ThematicBreak -> {
                    blocks.add(ContentBlock.Separator())
                }
            }
            node = node.next
        }

        val metadata = BookMetadata(title = file.nameWithoutExtension)
        return ParseResult(blocks, metadata, toc)
    }

    private fun parseParagraph(node: Paragraph): List<ContentBlock> {
        val firstChild = node.firstChild

        // Standalone image: paragraph containing only an Image node
        if (firstChild is Image && firstChild.next == null) {
            val alt = extractText(firstChild).ifEmpty { "无法描述的图片" }
            return listOf(ContentBlock.Image(alt, firstChild.destination))
        }

        // Standalone link: paragraph containing only a Link node (not Image)
        if (firstChild is Link && firstChild !is Image && firstChild.next == null) {
            val text = extractText(firstChild)
            return listOf(ContentBlock.Link(text, firstChild.destination))
        }

        val text = extractText(node)

        // Block formula on its own line: $$...$$
        val blockFormula = Regex("""^\$\$(.+)\$\$$""", RegexOption.DOT_MATCHES_ALL).find(text.trim())
        if (blockFormula != null) {
            return listOf(ContentBlock.Formula(blockFormula.groupValues[1].trim(), display = true))
        }

        // Inline formula on its own line: $...$
        val inlineFormula = Regex("""^\$([^$]+)\$$""").find(text.trim())
        if (inlineFormula != null) {
            return listOf(ContentBlock.Formula(inlineFormula.groupValues[1].trim(), display = false))
        }

        return if (text.isNotBlank()) listOf(ContentBlock.Paragraph(text)) else emptyList()
    }

    /**
     * Recursively extract plain text from a CommonMark node tree.
     * Inline formatting (bold, italic, code) is stripped to produce readable text.
     */
    private fun extractText(node: Node): String {
        val sb = StringBuilder()
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is Text -> sb.append(child.literal)
                is SoftLineBreak -> sb.append(" ")
                is HardLineBreak -> sb.append("\n")
                is Code -> sb.append(child.literal)
                is Emphasis -> sb.append(extractText(child))
                is StrongEmphasis -> sb.append(extractText(child))
                is Link -> sb.append(extractText(child))
                is Image -> sb.append(extractText(child))
                else -> sb.append(extractText(child))
            }
            child = child.next
        }
        return sb.toString().trim()
    }

    private fun parseTable(tableBlock: TableBlock): ContentBlock {
        val headers = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()

        var child = tableBlock.firstChild
        while (child != null) {
            when (child) {
                is TableHead -> {
                    var row = child.firstChild
                    while (row != null) {
                        if (row is TableRow) {
                            headers.addAll(extractTableRow(row))
                        }
                        row = row.next
                    }
                }
                is TableBody -> {
                    var row = child.firstChild
                    while (row != null) {
                        if (row is TableRow) {
                            rows.add(extractTableRow(row))
                        }
                        row = row.next
                    }
                }
            }
            child = child.next
        }

        val tableText = buildString {
            append(headers.joinToString(" | "))
            append("\n")
            append("---|".repeat(headers.size).trimEnd('|'))
            append("\n")
            for (row in rows) {
                append(row.joinToString(" | "))
                append("\n")
            }
        }.trim()

        return ContentBlock.Table(tableText, headers, rows)
    }

    private fun extractTableRow(row: TableRow): List<String> {
        val cells = mutableListOf<String>()
        var cell = row.firstChild
        while (cell != null) {
            if (cell is TableCell) {
                cells.add(extractText(cell))
            }
            cell = cell.next
        }
        return cells
    }
}
