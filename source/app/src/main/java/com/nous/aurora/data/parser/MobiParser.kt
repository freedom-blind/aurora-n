package com.nous.aurora.data.parser

import com.nous.aurora.data.model.ContentBlock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.text.RegexOption

class MobiParser(
    private val tempDir: File? = null
) : BookParser {

    override val supportedExtensions = listOf("mobi", "azw", "azw3")

    override fun parse(filePath: String): ParseResult {
        val file = File(filePath)
        val raf = RandomAccessFile(file, "r")
        return try {
            val palmHdr = ByteArray(78)
            raf.readFully(palmHdr)
            val dbName = String(palmHdr, 0, 32).trimEnd('\u0000')

            if (dbName.startsWith("CR!") || dbName.startsWith("ER!") || dbName.startsWith("\$!")) {
                raf.close()
                return ParseResult(listOf(ContentBlock.Paragraph(
                    "此文件受 DRM 保护。\n\n文件名：${file.nameWithoutExtension}"
                )), BookMetadata(title = file.nameWithoutExtension))
            }

            val buf = ByteBuffer.wrap(palmHdr).order(ByteOrder.BIG_ENDIAN)
            val numRecords = buf.getShort(76).toInt() and 0xFFFF
            val recordOffsets = mutableListOf<Int>()
            for (i in 0 until numRecords) {
                recordOffsets.add(raf.readInt())
                raf.readByte(); raf.readInt()
            }

            val r0Off = recordOffsets[0].toLong()
            val r0Size = (if (numRecords > 1) recordOffsets[1] - recordOffsets[0] else 1024)
                .coerceAtLeast(0).coerceAtMost(1024 * 1024)
            raf.seek(r0Off)
            val r0 = ByteArray(r0Size)
            raf.readFully(r0)
            val hdr = parseMobiHeader(r0)

            // 尝试 native 解压
            if (hdr.encryptionType == 4) {
                val nativeText = tryNativeDecompression(file)
                if (nativeText != null && nativeText.length > 200) {
                    raf.close()
                    return parseHtmlWithToc(nativeText, file.nameWithoutExtension, hdr)
                }
            }

            raf.seek(0)
            parseInternal(raf, file, recordOffsets, numRecords, hdr)
        } catch (e: Throwable) {
            try { rawTextFallback(raf, file, file.nameWithoutExtension, "") }
            catch (e2: Throwable) {
                ParseResult(listOf(ContentBlock.Paragraph("无法解析此文件")),
                    BookMetadata(title = file.nameWithoutExtension))
            }
        } finally {
            try { raf.close() } catch (_: Throwable) {}
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  HTML 解析：提取 TOC、链接、anchors
    // ════════════════════════════════════════════════════════════════

    private fun parseHtmlWithToc(html: String, nameWithoutExt: String, hdr: MobiHeaderInfo): ParseResult {
        val cleaned = cleanHtml(html)
        val blocks = parseTextToBlocks(cleaned)

        // 从 HTML 中提取内部链接和锚点
        val linkMap = buildLinkMap(html, blocks)

        // 提取 TOC：优先用 HTML 中的 NCX 或 NAV，fallback 到标题检测
        val toc = extractNcxFromHtml(html, linkMap)
            .ifEmpty { extractNavFromHtml(html, linkMap) }
            .ifEmpty { extractHeadingToc(blocks) }

        return ParseResult(blocks, BookMetadata(
            title = hdr.title ?: nameWithoutExt,
            author = hdr.author ?: "",
            publisher = hdr.publisher ?: "",
            language = hdr.language ?: ""
        ), toc, linkMap)
    }

    /**
     * 从 HTML 中提取 NCX 目录（MOBI/AZW 格式）。
     */
    private fun extractNcxFromHtml(html: String, linkMap: Map<String, Int>): List<TocEntry> {
        val navMapRegex = Regex(
            """<navMap[^>]*>(.*?)</navMap>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val navMapMatch = navMapRegex.find(html)
        if (navMapMatch != null) {
            return parseNavMap(navMapMatch.groupValues[1], linkMap)
        }
        return emptyList()
    }

    /**
     * 从 HTML 中提取 NAV 目录（AZW3/KF8 格式）。
     * AZW3 使用 EPUB 风格的 <nav> 元素，包含 <ol> 和 <a> 标签。
     */
    private fun extractNavFromHtml(html: String, linkMap: Map<String, Int>): List<TocEntry> {
        // 找 <nav epub:type="toc"> 或 <nav id="toc">
        val navRegex = Regex(
            """<nav[^>]*toc["'][^>]*>(.*?)</nav>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val navMatch = navRegex.find(html) ?: return emptyList()

        val navContent = navMatch.groupValues[1]

        // 解析 <ol> 中的 <li> 项
        val liRegex = Regex(
            """<li[^>]*>(.*?)</li>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        return parseNavLiItems(navContent, linkMap, 0)
    }

    private fun parseNavLiItems(html: String, linkMap: Map<String, Int>, depth: Int): List<TocEntry> {
        val result = mutableListOf<TocEntry>()
        val liRegex = Regex(
            """<li[^>]*>(.*?)</li>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        for (liMatch in liRegex.findAll(html)) {
            val liContent = liMatch.groupValues[1]

            // 提取 <a> 标签
            val aRegex = Regex(
                """<a[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            val aMatch = aRegex.find(liContent)

            if (aMatch != null) {
                val href = aMatch.groupValues[1]
                val title = aMatch.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                if (title.isNotBlank()) {
                    val paraIndex = resolveMobiSrc(href, linkMap)

                    // 递归解析子 <ol>
                    val olRegex = Regex(
                        """<ol[^>]*>(.*?)</ol>""",
                        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                    )
                    val olMatch = olRegex.find(liContent)
                    val children = if (olMatch != null) {
                        parseNavLiItems(olMatch.groupValues[1], linkMap, depth + 1)
                    } else emptyList()

                    result.add(TocEntry(title, paraIndex, depth, children))
                }
            } else {
                // 没有 <a> 但有子 <ol>，递归
                val olRegex = Regex(
                    """<ol[^>]*>(.*?)</ol>""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                )
                val olMatch = olRegex.find(liContent)
                if (olMatch != null) {
                    result.addAll(parseNavLiItems(olMatch.groupValues[1], linkMap, depth))
                }
            }
        }
        return result
    }

    private fun parseNavMap(navMapXml: String, linkMap: Map<String, Int>): List<TocEntry> {
        val result = mutableListOf<TocEntry>()
        val navPointRegex = Regex(
            """<navPoint[^>]*>(.*?)</navPoint>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        for (npMatch in navPointRegex.findAll(navMapXml)) {
            val npContent = npMatch.groupValues[1]

            // 提取标题
            val labelMatch = Regex(
                """<text[^>]*>(.*?)</text>""",
                RegexOption.IGNORE_CASE
            ).find(npContent)
            val title = labelMatch?.groupValues?.get(1)?.trim() ?: "无标题"

            // 提取 src
            val srcMatch = Regex(
                """<content[^>]*src=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(npContent)
            val src = srcMatch?.groupValues?.get(1) ?: ""

            val paraIndex = resolveMobiSrc(src, linkMap)

            // 递归解析子 navPoint
            val innerNavMap = Regex(
                """<navMap[^>]*>(.*?)</navMap>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            ).find(npContent)

            val children = if (innerNavMap != null) {
                parseNavMap(innerNavMap.groupValues[1], linkMap)
            } else {
                parseNavMap(npContent, linkMap)
            }

            result.add(TocEntry(title, paraIndex, 0, children))
        }

        return result
    }

    private fun resolveMobiSrc(src: String, linkMap: Map<String, Int>): Int {
        if (src.isBlank()) return 0

        // 直接匹配
        linkMap[src]?.let { return it }

        // 去掉 # 前缀后匹配
        if (src.startsWith("#")) {
            linkMap[src]?.let { return it }
            linkMap[src.substring(1)]?.let { return it }
        }

        // 提取 fragment 部分
        val fragment = src.substringAfterLast('#', "")
        if (fragment.isNotEmpty()) {
            linkMap["#$fragment"]?.let { return it }
            linkMap[fragment]?.let { return it }
        }

        // 尝试仅文件名匹配
        val basename = File(src).name
        linkMap[basename]?.let { return it }

        return 0
    }

    /**
     * 从 HTML 和 blocks 构建链接映射。
     * - 找 <a name="X"> 或 id="X" 作为锚点
     * - 找 <a href="..."> 作为链接源
     * - 建立 href → paragraphIndex 映射
     */
    private fun buildLinkMap(html: String, blocks: List<ContentBlock>): Map<String, Int> {
        val linkMap = mutableMapOf<String, Int>()

        // 1. 从标题建立 slug 映射
        for (i in blocks.indices) {
            val b = blocks[i]
            if (b is ContentBlock.Heading) {
                val slug = b.text.lowercase()
                    .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "-")
                    .trim('-')
                linkMap["#$slug"] = i
            }
        }

        // 2. 找 HTML 中的锚点 (<a name="X"> 或 id="X")
        val anchorRegex = Regex(
            """(?:<a[^>]*name=["']([^"']+)["'][^>]*>|id=["']([^"']+)["'])""",
            RegexOption.IGNORE_CASE
        )

        val anchorPositions = mutableListOf<Pair<String, Int>>()
        for (match in anchorRegex.findAll(html)) {
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (name.isNotBlank()) {
                anchorPositions.add(name to match.range.first)
            }
        }

        var charCount = 0
        for ((anchorName, anchorCharPos) in anchorPositions) {
            charCount = 0
            for (i in blocks.indices) {
                charCount += blocks[i].text.length + 1
                if (charCount > anchorCharPos) {
                    linkMap["#$anchorName"] = i
                    linkMap[anchorName] = i
                    break
                }
            }
        }

        // 3. 找 <a href="..."> 链接，建立 href → 段落映射
        val linkRegex = Regex(
            """<a[^>]*href=["']([^"']+)["'][^>]*>""",
            RegexOption.IGNORE_CASE
        )
        for (match in linkRegex.findAll(html)) {
            val href = match.groupValues[1]
            if (!href.startsWith("http://") && !href.startsWith("https://") && href.isNotBlank()) {
                charCount = 0
                for (i in blocks.indices) {
                    charCount += blocks[i].text.length + 1
                    if (charCount > match.range.first) {
                        break
                    }
                }
            }
        }

        return linkMap
    }

    private fun extractHeadingToc(blocks: List<ContentBlock>): List<TocEntry> {
        return blocks.mapIndexedNotNull { i, b ->
            if (b is ContentBlock.Heading) TocEntry(b.text, i, b.level) else null
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Native 解压
    // ════════════════════════════════════════════════════════════════

    private fun tryNativeDecompression(file: File): String? {
        return try {
            val dir = tempDir ?: File(System.getProperty("java.io.tmpdir", "/data/local/tmp"))
            if (!dir.exists()) dir.mkdirs()
            val tmpFile = File(dir, "mobi_${System.currentTimeMillis()}.tmp")
            file.inputStream().use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            val resultPath = MobiNative.extractText(tmpFile.absolutePath)
            val resultFile = if (resultPath != null) File(resultPath) else null
            val text = if (resultFile != null && resultFile.exists() && resultFile.length() > 100) {
                val html = resultFile.readText()
                resultFile.delete()
                html
            } else null
            tmpFile.delete()
            File("${tmpFile.absolutePath}.epub").delete()
            File("${tmpFile.absolutePath}.html").delete()
            text
        } catch (e: Throwable) { null }
    }

    // ════════════════════════════════════════════════════════════════
    //  内部解析（fallback）
    // ════════════════════════════════════════════════════════════════

    private fun parseInternal(raf: RandomAccessFile, file: File,
                              recordOffsets: List<Int>, numRecords: Int,
                              hdr: MobiHeaderInfo): ParseResult {
        val title = hdr.title ?: file.nameWithoutExtension

        if (recordOffsets.getOrElse(1) { 0 } > raf.length()) {
            return rawTextFallback(raf, file, title, hdr.author ?: "")
        }

        val sb = StringBuilder()
        for (i in 1 until minOf(numRecords, 5000)) {
            if (sb.length >= 2_000_000) break
            val off = recordOffsets[i].toLong()
            if (off < 0 || off >= raf.length()) continue
            val sz = (if (i < numRecords - 1) recordOffsets[i + 1] - recordOffsets[i]
                      else raf.length().toInt() - off.toInt())
                .coerceAtLeast(0).coerceAtMost(500_000)
            if (sz <= 0) continue
            val trailing = hdr.extraBytes.toInt().let { if (it and 0x8000 != 0) 0 else it and 0x7FFF }
            val textSize = (sz - trailing).coerceAtLeast(0)
            if (textSize <= 0 || textSize > 500_000) continue
            raf.seek(off)
            val data = ByteArray(textSize.coerceAtMost(sz))
            raf.readFully(data)
            sb.append(when {
                hdr.encryptionType == 2 -> decompressPalmDoc(data)
                hdr.encryptionType == 0 -> String(data, Charsets.UTF_8)
                else -> tryExtractText(data)
            })
        }
        val raw = if (sb.isEmpty()) tryReadText(raf, recordOffsets) else sb.toString()
        return parseHtmlWithToc(raw, file.nameWithoutExtension, hdr)
    }

    private fun rawTextFallback(raf: RandomAccessFile, file: File, title: String, author: String): ParseResult {
        try {
            val bytes = ByteArray(raf.length().toInt().coerceAtMost(5_000_000))
            raf.seek(0); raf.readFully(bytes)
            val htmlStart = findBytes(bytes, "<html".toByteArray())
            if (htmlStart >= 0) {
                val htmlEnd = findBytes(bytes, "</html>".toByteArray(), htmlStart)
                val endPos = if (htmlEnd >= 0) htmlEnd + 7 else minOf(bytes.size, htmlStart + 2_000_000)
                val htmlText = String(bytes.copyOfRange(htmlStart, minOf(endPos, bytes.size)), Charsets.UTF_8)
                return parseHtmlWithToc(htmlText, file.nameWithoutExtension,
                    MobiHeaderInfo(title = title, author = author))
            }
            val sb = StringBuilder()
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                if (c in 0x20..0x7E || c == '\n'.code || c == '\r'.code || c == '\t'.code) sb.append(b.toChar())
            }
            val text = sb.toString()
            if (text.length.toFloat() / bytes.size < 0.3f || text.length < 200) {
                return ParseResult(listOf(ContentBlock.Paragraph(
                    "此文件使用 HUFF/CDIC 压缩格式，暂不支持解析。\n文件名：$title\n建议使用 Calibre 转换为 EPUB。"
                )), BookMetadata(title = title, author = author))
            }
            val blocks = text.split(Regex("\n{2,}|\n")).map { it.trim() }
                .filter { it.length > 3 }.map { ContentBlock.Paragraph(it) }
            val toc = extractHeadingToc(blocks)
            return ParseResult(blocks, BookMetadata(title = title, author = author), toc)
        } catch (e: Throwable) {
            return ParseResult(listOf(ContentBlock.Paragraph("无法解析此 MOBI 文件")),
                BookMetadata(title = title, author = author))
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  工具方法
    // ════════════════════════════════════════════════════════════════

    private fun findBytes(h: ByteArray, n: ByteArray, from: Int = 0): Int {
        for (i in from until h.size - n.size) {
            if ((0 until n.size).all { h[i + it] == n[it] }) return i
        }
        return -1
    }

    data class MobiHeaderInfo(val title: String? = null, val author: String? = null,
        val publisher: String? = null, val language: String? = null,
        val encryptionType: Int = 0, val extraBytes: Short = 0)

    private fun parseMobiHeader(r0: ByteArray): MobiHeaderInfo {
        var title: String? = null; var author: String? = null
        var publisher: String? = null; var language: String? = null
        var encType = 0; var extraBytes: Short = 0
        var off = -1
        for (i in 0 until r0.size - 4) {
            if (r0[i] == 'M'.code.toByte() && r0[i+1] == 'O'.code.toByte() &&
                r0[i+2] == 'B'.code.toByte() && r0[i+3] == 'I'.code.toByte()) { off = i; break }
        }
        if (off >= 16 && off + 20 < r0.size) {
            val m = ByteBuffer.wrap(r0, off, minOf(r0.size - off, 512)).order(ByteOrder.BIG_ENDIAN)
            m.position(36); val no = m.getInt(); val nl = m.getInt()
            if (no > 0 && nl > 0 && no + nl <= r0.size) title = String(r0, no, nl).trim()
            m.position(20); val ml = m.getInt()
            val ep = off + 16 + ml
            if (ep + 12 <= r0.size) {
                val eb = ByteBuffer.wrap(r0, ep, r0.size - ep).order(ByteOrder.BIG_ENDIAN)
                if (eb.getInt() == 0x45585448) {
                    eb.getInt(); val cnt = eb.getInt()
                    for (i in 0 until cnt.coerceAtMost(100)) {
                        if (eb.remaining() < 8) break
                        val type = eb.getInt(); val sz = eb.getInt()
                        val dl = sz - 8
                        if (dl <= 0 || eb.remaining() < dl) break
                        val d = ByteArray(dl); eb.get(d)
                        when (type) { 100 -> author = String(d).trim(); 101 -> publisher = String(d).trim()
                            524 -> language = String(d).trim(); 503 -> title = String(d).trim() }
                    }
                }
            }
            if (off + 3 < r0.size) encType = (r0[off + 3].toInt() and 0xFF) ushr 4
            if (off + 18 < r0.size) extraBytes = ByteBuffer.wrap(r0, off + 18, 2).order(ByteOrder.BIG_ENDIAN).short
        }
        return MobiHeaderInfo(title, author, publisher, language, encType, extraBytes)
    }

    private fun decompressPalmDoc(data: ByteArray): String {
        val sb = StringBuilder(minOf(data.size * 2, 100_000))
        var i = 0
        while (i < data.size && sb.length < 500_000) {
            val b = data[i].toInt() and 0xFF
            when {
                b == 0 -> { if (i + 1 < data.size) sb.append(data[++i].toChar()) }
                b in 1..8 -> { val end = minOf(i + b, data.size - 1); while (i < end) sb.append(data[++i].toChar()) }
                b in 9..0x7F -> sb.append(b.toChar())
                b in 0xC0..0xFF -> { sb.append(' '); sb.append((b xor 0x80).toChar()) }
                b in 0x80..0xBF -> {
                    if (i + 1 >= data.size) { i++; continue }
                    val next = data[++i].toInt() and 0xFF
                    val dist = ((b shl 8 or next) shr 3) and 0x7FF
                    val len = (next and 7) + 3
                    val start = sb.length - dist
                    if (start >= 0 && start < sb.length)
                        for (j in 0 until len) { val idx = start + j; if (idx < sb.length) sb.append(sb[idx]) else break }
                }
            }
            i++
        }
        return sb.toString()
    }

    private fun cleanHtml(html: String): String {
        var text = html
            .replace(Regex("</?p[^>]*>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("</?div[^>]*>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<br[^>]*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?h[1-6][^>]*>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n  ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        text = text.replace(Regex("\n[ \t]*\n[ \t\n]*"), "\n\n")
        text = text.replace(Regex("[ \t]+"), " ")
        return text.trim()
    }

    private fun parseTextToBlocks(text: String): List<ContentBlock> {
        val maxParaLen = 2000
        val blocks = text.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(5000)
            .flatMap { para ->
                when {
                    para.length > maxParaLen ->
                        para.split(Regex("(?<=[.。!！?？;；…～~\\n])\\s*"))
                            .map { it.trim() }.filter { it.isNotBlank() }
                    para.contains('\n') ->
                        para.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                    else -> listOf(para)
                }
            }
            .take(5000)
            .map { ContentBlock.Paragraph(it.toCharArray().concatToString()) }
        return blocks
    }

    private fun tryExtractText(data: ByteArray): String {
        val sb = StringBuilder(); var run = 0
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E || c in 0x80..0xFF || c == '\n'.code || c == '\r'.code || c == '\t'.code) run++
            else { if (run >= 3) sb.append(String(data, sb.length, run, Charsets.UTF_8)); run = 0 }
        }
        if (run >= 3) sb.append(String(data, data.size - run, run, Charsets.UTF_8))
        return sb.toString()
    }

    private fun tryReadText(raf: RandomAccessFile, offsets: List<Int>): String {
        val sb = StringBuilder()
        for (i in 1 until offsets.size) {
            val off = offsets[i].toLong(); if (off < 0 || off >= raf.length()) continue
            val sz = (if (i < offsets.size - 1) offsets[i+1] - offsets[i] else raf.length().toInt() - off.toInt())
                .coerceAtLeast(0).coerceAtMost(500_000)
            if (sz > 0) { raf.seek(off); val d = ByteArray(sz); raf.readFully(d); sb.append(String(d, Charsets.UTF_8)) }
        }
        return sb.toString()
    }
}