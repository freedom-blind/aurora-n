package com.nous.aurora.data.parser

import com.nous.aurora.data.model.ContentBlock
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class Fb2Parser : BookParser {

    override val supportedExtensions = listOf("fb2")

    override fun parse(filePath: String): ParseResult {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val doc = factory.newDocumentBuilder().parse(File(filePath))

        // Extract metadata
        val titleInfo = doc.getElementsByTagName("title-info").item(0)
        val metadata = if (titleInfo != null) {
            fun tag(name: String): String {
                val nodes = (titleInfo as org.w3c.dom.Element).getElementsByTagName(name)
                return if (nodes.length > 0) nodes.item(0).textContent.trim() else ""
            }
            BookMetadata(
                title = tag("book-title"),
                author = tag("author") ?: run {
                    val authorEl = (titleInfo as org.w3c.dom.Element).getElementsByTagName("author").item(0)
                    if (authorEl != null) {
                        val fn = (authorEl as org.w3c.dom.Element).getElementsByTagName("first-name")
                        val ln = authorEl.getElementsByTagName("last-name")
                        val first = if (fn.length > 0) fn.item(0).textContent.trim() else ""
                        val last = if (ln.length > 0) ln.item(0).textContent.trim() else ""
                        "$first $last".trim()
                    } else ""
                },
                publisher = tag("publisher"),
                year = tag("date")?.take(4) ?: "",
                language = tag("lang"),
                description = tag("annotation")
            )
        } else {
            BookMetadata(title = File(filePath).nameWithoutExtension)
        }

        val blocks = mutableListOf<ContentBlock>()
        val toc = mutableListOf<TocEntry>()

        // Extract body sections
        val bodyElements = doc.getElementsByTagName("body")
        for (bi in 0 until bodyElements.length) {
            val body = bodyElements.item(bi)
            parseFb2Element(body, blocks, toc)
        }

        return ParseResult(blocks, metadata, toc)
    }

    private fun parseFb2Element(
        element: org.w3c.dom.Node,
        blocks: MutableList<ContentBlock>,
        toc: MutableList<TocEntry>,
        level: Int = 0
    ) {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when (child.nodeName) {
                "section" -> {
                    val titleEl = (child as org.w3c.dom.Element).getElementsByTagName("title").item(0)
                    if (titleEl != null) {
                        val titleText = extractTextContent(titleEl).trim()
                        if (titleText.isNotBlank()) {
                            blocks.add(ContentBlock.Heading(titleText, level + 1))
                            toc.add(TocEntry(titleText, blocks.size - 1, level + 1))
                        }
                    }
                    parseFb2Element(child, blocks, toc, level + 1)
                }
                "title" -> {
                    // Already handled in section; skip standalone titles at body level
                    if (element.nodeName == "body") {
                        val text = extractTextContent(child).trim()
                        if (text.isNotBlank()) {
                            blocks.add(ContentBlock.Heading(text, 1))
                            toc.add(TocEntry(text, blocks.size - 1, 1))
                        }
                    }
                }
                "p" -> {
                    val text = extractTextContent(child).trim()
                    if (text.isNotBlank()) {
                        blocks.add(ContentBlock.Paragraph(text))
                    }
                }
                "subtitle" -> {
                    val text = extractTextContent(child).trim()
                    if (text.isNotBlank()) {
                        blocks.add(ContentBlock.Heading(text, 2))
                    }
                }
                "epigraph" -> {
                    val text = extractTextContent(child).trim()
                    if (text.isNotBlank()) {
                        blocks.add(ContentBlock.Paragraph(text))
                    }
                }
                "image" -> {
                    val href = (child as? org.w3c.dom.Element)?.getAttribute("l:href")
                        ?: (child as? org.w3c.dom.Element)?.getAttribute("xlink:href") ?: ""
                    val alt = "图片"
                    blocks.add(ContentBlock.Image(alt, href.removePrefix("#")))
                }
                "empty-line" -> {
                    if (blocks.isNotEmpty() && blocks.last() !is ContentBlock.Separator) {
                        blocks.add(ContentBlock.Separator())
                    }
                }
            }
        }
    }

    private fun extractTextContent(node: org.w3c.dom.Node): String {
        val sb = StringBuilder()
        extractText(node, sb)
        return sb.toString()
    }

    private fun extractText(node: org.w3c.dom.Node, sb: StringBuilder) {
        if (node.nodeType == org.w3c.dom.Node.TEXT_NODE) {
            sb.append(node.textContent)
        }
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when (child.nodeName) {
                "a" -> {
                    val href = (child as? org.w3c.dom.Element)?.getAttribute("l:href")
                        ?: (child as? org.w3c.dom.Element)?.getAttribute("xlink:href") ?: ""
                    val text = child.textContent
                    sb.append(text)
                    // We could add as a Link block instead, but keeping inline for now
                }
                "emphasis", "strong", "strikethrough" -> {
                    val text = child.textContent
                    sb.append(text)
                }
                else -> extractText(child, sb)
            }
        }
    }
}
