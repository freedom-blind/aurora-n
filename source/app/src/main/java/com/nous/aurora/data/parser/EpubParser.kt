package com.nous.aurora.data.parser

import com.nous.aurora.data.model.ContentBlock
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class EpubParser : BookParser {

    override val supportedExtensions = listOf("epub")

    override fun parse(filePath: String): ParseResult {
        return ZipFile(File(filePath)).use { zip ->
            val opfPath = findOpfPath(zip) ?: throw Exception("Invalid EPUB: no OPF found")
            val opfXml = parseXml(zip.getInputStream(zip.getEntry(opfPath)).readBytes())
            val rootDir = File(opfPath).parent?.let { if (it.isNotEmpty()) "$it/" else "" } ?: ""

            val metadata = extractMetadata(opfXml)
            val spineItems = extractSpine(opfXml)
            val manifest = extractManifest(opfXml)

            val blocks = mutableListOf<ContentBlock>()
            val linkMap = mutableMapOf<String, Int>()
            for (idref in spineItems) {
                val href = manifest[idref] ?: continue
                val entryPath = rootDir + href
                val entry = zip.getEntry(entryPath) ?: continue
                val content = String(zip.getInputStream(entry).readBytes())
                val startIndex = blocks.size
                blocks.addAll(parseXhtml(content, zip, rootDir))
                linkMap[href] = startIndex
                val basename = File(href).name
                if (basename != href) linkMap[basename] = startIndex

                // Map element IDs to block indices
                val endIndex = blocks.size
                for (match in Regex("""id=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(content)) {
                    val id = match.groupValues[1]
                    var paraIdx = startIndex
                    var charCount = 0
                    for (i in startIndex until endIndex) {
                        charCount += blocks[i].text.length + 1
                        if (charCount > match.range.first) {
                            paraIdx = i; break
                        }
                    }
                    linkMap["${basename}#$id"] = paraIdx
                    linkMap["#$id"] = paraIdx
                }
            }

            buildAnchorMap(blocks, linkMap)

            // ── Complete TOC extraction ──
            // 1. Try NCX (EPUB2) or NAV (EPUB3) for author-defined TOC
            val ncxToc = extractNcxToc(zip, opfXml, rootDir, manifest, linkMap)
            val navToc = if (ncxToc.isEmpty()) extractNavToc(zip, opfXml, rootDir, manifest, linkMap) else emptyList()
            val authorToc = ncxToc.ifEmpty { navToc }

            // 2. Fallback: heading-based TOC
            val headingToc = extractHeadingToc(blocks)

            // 3. Use author TOC if available and more detailed, otherwise heading-based
            val toc = if (authorToc.isNotEmpty()) authorToc else headingToc

            ParseResult(blocks, metadata, toc, linkMap)
        }
    }

    override fun getCoverImage(filePath: String): ByteArray? {
        return ZipFile(File(filePath)).use { zip ->
            val opfPath = findOpfPath(zip) ?: return@use null
            val opfXml = parseXml(zip.getInputStream(zip.getEntry(opfPath)).readBytes())
            val rootDir = File(opfPath).parent?.let { if (it.isNotEmpty()) "$it/" else "" } ?: ""
            val manifest = extractManifest(opfXml)

            val coverId = findMetaValue(opfXml, "cover")
            val coverHref = coverId?.let { manifest[it] }
                ?: manifest.entries.firstOrNull {
                    it.value.contains("cover", ignoreCase = true) &&
                            (it.value.endsWith(".jpg", true) || it.value.endsWith(".png", true) || it.value.endsWith(
                                ".jpeg",
                                true
                            ))
                }?.value

            if (coverHref != null) {
                zip.getEntry(rootDir + coverHref)?.let { zip.getInputStream(it).readBytes() }
            } else null
        }
    }

    // ── OPF parsing ──

    private fun findOpfPath(zip: ZipFile): String? {
        val containerEntry = zip.getEntry("META-INF/container.xml")
        if (containerEntry != null) {
            val xml = String(zip.getInputStream(containerEntry).readBytes())
            return """full-path="([^"]+)"""".toRegex().find(xml)?.groupValues?.get(1)
        }
        return zip.entries().asSequence().firstOrNull { it.name.endsWith(".opf", true) }?.name
    }

    private fun extractMetadata(opfXml: org.w3c.dom.Document): BookMetadata {
        fun el(name: String): String {
            val nodes = opfXml.getElementsByTagNameNS("*", name)
            return if (nodes.length > 0) nodes.item(0).textContent.trim() else ""
        }
        return BookMetadata(
            title = el("title"), author = el("creator"), publisher = el("publisher"),
            year = el("date").take(4), language = el("language"), description = el("description")
        )
    }

    private fun extractSpine(opfXml: org.w3c.dom.Document): List<String> {
        val items = opfXml.getElementsByTagNameNS("*", "itemref")
        return (0 until items.length).map {
            (items.item(it) as org.w3c.dom.Element).getAttribute("idref")
        }
    }

    private fun extractManifest(opfXml: org.w3c.dom.Document): Map<String, String> {
        val items = opfXml.getElementsByTagNameNS("*", "item")
        return (0 until items.length).associate { i ->
            val el = items.item(i) as org.w3c.dom.Element
            el.getAttribute("id") to el.getAttribute("href")
        }
    }

    private fun findMetaValue(opfXml: org.w3c.dom.Document, name: String): String? {
        val metas = opfXml.getElementsByTagNameNS("*", "meta")
        for (i in 0 until metas.length) {
            val el = metas.item(i) as org.w3c.dom.Element
            if (el.getAttribute("name") == name) return el.getAttribute("content")
        }
        return null
    }

    // ════════════════════════════════════════════════════════════════
    //  NCX TOC 解析 (EPUB2)
    // ════════════════════════════════════════════════════════════════

    private fun extractNcxToc(
        zip: ZipFile,
        opfXml: org.w3c.dom.Document,
        rootDir: String,
        manifest: Map<String, String>,
        linkMap: Map<String, Int>
    ): List<TocEntry> {
        // Find NCX file: spine toc attribute or manifest item
        val spine = opfXml.getElementsByTagNameNS("*", "spine")
        val tocId = if (spine.length > 0)
            (spine.item(0) as org.w3c.dom.Element).getAttribute("toc") else ""
        val ncxHref = if (tocId.isNotEmpty()) manifest[tocId] else
            manifest.entries.firstOrNull {
                it.value.endsWith(".ncx", ignoreCase = true)
            }?.value

        if (ncxHref == null) return emptyList()

        val ncxPath = rootDir + ncxHref
        val ncxEntry = zip.getEntry(ncxPath) ?: return emptyList()
        val ncxContent = String(zip.getInputStream(ncxEntry).readBytes())

        return try {
            val ncxXml = parseXml(ncxContent.toByteArray())
            val navMap = ncxXml.getElementsByTagNameNS("*", "navMap")
            if (navMap.length == 0) return emptyList()

            val navPoints = (navMap.item(0) as org.w3c.dom.Element)
                .getElementsByTagNameNS("*", "navPoint")
            parseNavPoints(navPoints, ncxHref, linkMap)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseNavPoints(
        navPoints: org.w3c.dom.NodeList,
        ncxFile: String,
        linkMap: Map<String, Int>
    ): List<TocEntry> {
        val result = mutableListOf<TocEntry>()
        for (i in 0 until navPoints.length) {
            val np = navPoints.item(i) as org.w3c.dom.Element

            // Get title from navLabel/text
            val navLabels = np.getElementsByTagNameNS("*", "navLabel")
            var title = "无标题"
            if (navLabels.length > 0) {
                val texts = (navLabels.item(0) as org.w3c.dom.Element)
                    .getElementsByTagNameNS("*", "text")
                if (texts.length > 0) {
                    title = texts.item(0).textContent.trim()
                }
            }

            // Get src from content
            val contents = np.getElementsByTagNameNS("*", "content")
            var src = ""
            if (contents.length > 0) {
                src = (contents.item(0) as org.w3c.dom.Element).getAttribute("src")
            }

            // Resolve paragraph index from src
            val paraIndex = resolveNcxSrc(src, ncxFile, linkMap)

            // Recursively parse child navPoints
            val childPoints = np.getElementsByTagNameNS("*", "navPoint")
            val children = parseNavPoints(childPoints, ncxFile, linkMap)

            result.add(TocEntry(title = title, paragraphIndex = paraIndex, level = 0, children = children))
        }
        return result
    }

    private fun resolveNcxSrc(src: String, ncxFile: String, linkMap: Map<String, Int>): Int {
        if (src.isBlank()) return 0

        // Split into file part and fragment
        val fragment = src.substringAfterLast('#', "")
        val filePart = src.substringBefore('#')

        // Try full src
        linkMap[src]?.let { return it }

        // Try fragment-only anchor
        if (fragment.isNotEmpty()) {
            linkMap["#$fragment"]?.let { return it }
        }

        // Try resolving relative to NCX file directory
        val ncxDir = File(ncxFile).parent?.let { if (it.isNotEmpty()) "$it/" else "" } ?: ""
        val resolvedFile = if (filePart.isNotEmpty()) resolvePath(ncxDir, filePart) else ""
        if (resolvedFile.isNotEmpty()) {
            linkMap[resolvedFile]?.let { fileStart ->
                if (fragment.isNotEmpty()) {
                    linkMap["$resolvedFile#$fragment"]?.let { return it }
                }
                return fileStart
            }
            // Try just the filename
            val basename = File(resolvedFile).name
            linkMap[basename]?.let { return it }
            if (fragment.isNotEmpty()) {
                linkMap["$basename#$fragment"]?.let { return it }
            }
        }

        // Try filePart directly
        if (filePart.isNotEmpty()) {
            linkMap[filePart]?.let { return it }
        }

        return 0
    }

    // ════════════════════════════════════════════════════════════════
    //  NAV TOC 解析 (EPUB3)
    // ════════════════════════════════════════════════════════════════

    private fun extractNavToc(
        zip: ZipFile,
        opfXml: org.w3c.dom.Document,
        rootDir: String,
        manifest: Map<String, String>,
        linkMap: Map<String, Int>
    ): List<TocEntry> {
        // Find NAV file: manifest item with properties="nav"
        val navHref = findNavHref(opfXml, manifest) ?: return emptyList()
        val navPath = rootDir + navHref
        val navEntry = zip.getEntry(navPath) ?: return emptyList()
        val navContent = String(zip.getInputStream(navEntry).readBytes())

        return try {
            // Parse as XHTML, find nav[epub:type="toc"] or nav with id="toc"
            val cleaned = navContent
                .replace("&nbsp;", " ").replace("&mdash;", "—")
                .replace("&ndash;", "–").replace("&rsquo;", "'")
                .replace("&lsquo;", "'").replace("&rdquo;", "\"")
                .replace("&ldquo;", "\"").replace("&hellip;", "…")

            // Find the <nav> element with epub:type="toc"
            val navRegex = Regex(
                """<nav[^>]*epub:type=["']toc["'][^>]*>(.*?)</nav>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            val navBody = navRegex.find(cleaned)?.groupValues?.get(1)
                ?: Regex(
                    """<nav[^>]*id=["']toc["'][^>]*>(.*?)</nav>""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                ).find(cleaned)?.groupValues?.get(1)
                ?: return emptyList()

            // Parse <ol> containing <li> items
            val olRegex = Regex(
                """<ol[^>]*>(.*?)</ol>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            val olMatch = olRegex.find(navBody) ?: return emptyList()
            parseNavOl(olMatch.groupValues[1], navHref, linkMap)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findNavHref(
        opfXml: org.w3c.dom.Document,
        manifest: Map<String, String>
    ): String? {
        // Search manifest items for properties="nav"
        val items = opfXml.getElementsByTagNameNS("*", "item")
        for (i in 0 until items.length) {
            val el = items.item(i) as org.w3c.dom.Element
            val props = el.getAttribute("properties")
            if (props.contains("nav", ignoreCase = true)) {
                return el.getAttribute("href")
            }
        }
        // Fallback: look for file named "nav.xhtml" or "nav.html"
        return manifest.values.firstOrNull {
            it.contains("nav", ignoreCase = true) &&
                    (it.endsWith(".xhtml", true) || it.endsWith(".html", true) || it.endsWith(".htm", true))
        }
    }

    private fun parseNavOl(
        olHtml: String,
        navFile: String,
        linkMap: Map<String, Int>
    ): List<TocEntry> {
        val result = mutableListOf<TocEntry>()
        val liRegex = Regex(
            """<li[^>]*>(.*?)</li>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        for (liMatch in liRegex.findAll(olHtml)) {
            val liContent = liMatch.groupValues[1]

            // Extract <a> tag
            val aRegex = Regex(
                """<a[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            val aMatch = aRegex.find(liContent)

            val (href, title) = if (aMatch != null) {
                aMatch.groupValues[1] to aMatch.groupValues[2]
                    .replace(Regex("<[^>]+>"), "").trim()
            } else {
                "" to liContent.replace(Regex("<[^>]+>"), "").trim()
            }

            if (title.isBlank()) continue

            val paraIndex = if (href.isNotEmpty()) resolveNcxSrc(href, navFile, linkMap) else 0

            // Recursively parse nested <ol>
            val innerOlRegex = Regex(
                """<ol[^>]*>(.*?)</ol>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            val innerOlMatch = innerOlRegex.find(liContent)
            val children = if (innerOlMatch != null) {
                parseNavOl(innerOlMatch.groupValues[1], navFile, linkMap)
            } else emptyList()

            result.add(TocEntry(title = title, paragraphIndex = paraIndex, level = 0, children = children))
        }
        return result
    }

    // ════════════════════════════════════════════════════════════════
    //  Heading-based TOC (fallback)
    // ════════════════════════════════════════════════════════════════

    private fun extractHeadingToc(blocks: List<ContentBlock>): List<TocEntry> {
        return blocks.mapIndexedNotNull { i, b ->
            if (b is ContentBlock.Heading) TocEntry(b.text, i, b.level) else null
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  XHTML parsing (regex-based)
    // ════════════════════════════════════════════════════════════════

    private fun parseXhtml(content: String, zip: ZipFile, rootDir: String): List<ContentBlock> {
        val cleaned = content
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace("&nbsp;", " ").replace("&mdash;", "—").replace("&ndash;", "–")
            .replace("&rsquo;", "'").replace("&lsquo;", "'").replace("&rdquo;", "\"")
            .replace("&ldquo;", "\"").replace("&hellip;", "…")

        val bodyContent =
            Regex("<body[^>]*>(.*?)</body>", RegexOption.DOT_MATCHES_ALL).find(cleaned)
                ?.groupValues?.get(1) ?: cleaned
        val blocks = mutableListOf<ContentBlock>()
        parseXhtmlRegex(bodyContent, blocks, zip, rootDir)
        return blocks
    }

    private fun parseXhtmlRegex(
        html: String,
        blocks: MutableList<ContentBlock>,
        zip: ZipFile,
        rootDir: String
    ) {
        val blockRegex = Regex(
            """<(h[1-6]|p|div|li|blockquote|table|img|hr)\b([^>]*?)>(.*?)</\1>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        var lastEnd = 0
        for (match in blockRegex.findAll(html).toList()) {
            val between = html.substring(lastEnd, match.range.first)
                .replace(Regex("<[^>]+>"), " ").trim()
            if (between.isNotBlank()) blocks.add(ContentBlock.Paragraph(between))

            val tag = match.groupValues[1].lowercase()
            val attrs = match.groupValues[2]
            val content = match.groupValues[3]

            when (tag) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val text = stripHtml(content).trim()
                    if (text.isNotBlank()) blocks.add(
                        ContentBlock.Heading(text, tag[1].digitToInt())
                    )
                }
                "p", "div", "li", "blockquote" -> parseInlineContent(content, blocks)
                "img" -> {
                    val src =
                        Regex("""src=["']([^"']+)["']""").find(attrs)?.groupValues?.get(1)
                    val alt =
                        Regex("""alt=["']([^"']*)["']""").find(attrs)?.groupValues?.get(1)
                            ?: "无法描述的图片"
                    if (src != null) {
                        val data = try {
                            zip.getEntry(resolvePath(rootDir, src))
                                ?.let { zip.getInputStream(it).readBytes() }
                        } catch (_: Exception) {
                            null
                        }
                        blocks.add(ContentBlock.Image(alt, src, data))
                    }
                }
                "hr" -> blocks.add(ContentBlock.Separator())
                "table" -> parseTable(content, blocks)
            }
            lastEnd = match.range.last + 1
        }
        val remaining = html.substring(lastEnd).replace(Regex("<[^>]+>"), " ").trim()
        if (remaining.isNotBlank()) blocks.add(ContentBlock.Paragraph(remaining))
    }

    private fun parseInlineContent(html: String, blocks: MutableList<ContentBlock>) {
        if (html.isBlank()) return
        val linkRegex = Regex(
            """<a\s[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val matches = linkRegex.findAll(html).toList()
        if (matches.isEmpty()) {
            stripHtml(html).trim().split("\n").map { it.trim() }.filter { it.isNotBlank() }
                .forEach { blocks.add(ContentBlock.Paragraph(it)) }
            return
        }
        var lastEnd = 0
        for (match in matches) {
            val before =
                html.substring(lastEnd, match.range.first).replace(Regex("<[^>]+>"), " ")
                    .decodeEntities().trim()
            if (before.isNotBlank()) blocks.add(ContentBlock.Paragraph(before))
            val linkText =
                match.groupValues[2].replace(Regex("<[^>]+>"), "").decodeEntities().trim()
            if (linkText.isNotBlank()) blocks.add(
                ContentBlock.Link(linkText, match.groupValues[1])
            )
            lastEnd = match.range.last + 1
        }
        val after =
            html.substring(lastEnd).replace(Regex("<[^>]+>"), " ").decodeEntities().trim()
        if (after.isNotBlank()) blocks.add(ContentBlock.Paragraph(after))
    }

    private fun parseTable(content: String, blocks: MutableList<ContentBlock>) {
        val trRegex = Regex(
            """<tr[^>]*>(.*?)</tr>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val trMatches = trRegex.findAll(content).toList()
        val headers = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()
        for ((ri, tr) in trMatches.withIndex()) {
            val cells =
                Regex(
                    """<t[dh][^>]*>(.*?)</t[dh]>""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                )
                    .findAll(tr.groupValues[1]).map { stripHtml(it.groupValues[1]).trim() }.toList()
            if (cells.isNotEmpty()) {
                if (ri == 0 && trMatches.size > 1) headers.addAll(cells) else rows.add(cells)
            }
        }
        if (rows.isEmpty()) return
        val text = buildString {
            if (headers.isNotEmpty()) {
                append(headers.joinToString(" | ")); append("\n")
                append(headers.joinToString(" | ") { "---" }); append("\n")
            }
            rows.forEach { append(it.joinToString(" | ")); append("\n") }
        }.trim()
        blocks.add(
            ContentBlock.Table(
                text,
                headers.ifEmpty { rows.first() },
                if (headers.isNotEmpty()) rows else rows.drop(1)
            )
        )
    }

    private fun stripHtml(html: String): String = html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " ").decodeEntities().replace(Regex("\\s+"), " ")

    private fun String.decodeEntities(): String =
        replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&nbsp;", " ")

    // ── TOC & anchors ──

    private fun buildAnchorMap(blocks: List<ContentBlock>, linkMap: MutableMap<String, Int>) {
        for (i in blocks.indices) {
            val block = blocks[i]
            if (block is ContentBlock.Heading) {
                val slug =
                    block.text.lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "-")
                        .trim('-')
                linkMap["#$slug"] = i
                linkMap[slug] = i
            }
        }
    }

    private fun resolvePath(rootDir: String, href: String): String {
        if (href.startsWith("/")) return href.removePrefix("/")
        return (if (rootDir.endsWith("/")) rootDir else "$rootDir/") + href
    }

    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isIgnoringComments = true
        }.newDocumentBuilder().parse(bytes.inputStream())
    }
}
