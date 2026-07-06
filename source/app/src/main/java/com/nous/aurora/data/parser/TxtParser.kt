package com.nous.aurora.data.parser

import android.content.Context
import android.content.SharedPreferences
import com.nous.aurora.data.model.ContentBlock
import com.nous.aurora.util.EncodingUtil
import java.io.File

class TxtParser : BookParser {

    override val supportedExtensions = listOf("txt")

    /** 可通过外部注入 SharedPreferences；为空时智能分章节默认开启 */
    var prefs: SharedPreferences? = null

    /** 最近一次解析检测到的编码 */
    var lastDetectedEncoding: String = "UTF-8"
        private set

    // 中文数字映射
    private val cnDigits = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )
    private val cnUnits = mapOf('十' to 10, '百' to 100, '千' to 1000, '万' to 10000)

    override fun parse(filePath: String): ParseResult {
        val file = File(filePath)
        val result = EncodingUtil.readWithEncodingDetection(file)
        lastDetectedEncoding = result.charset.split(" ")[0]  // 取编码名，去掉备注
        val content = result.text
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val title = file.nameWithoutExtension
        val lines = content.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(5000)

        val blocks = mutableListOf<ContentBlock>()
        for (para in lines) {
            val subLines = para.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            for (sl in subLines) {
                blocks.add(ContentBlock.Paragraph(sl))
            }
        }

        // 智能分章节
        val smartChapter = prefs?.getBoolean("smart_chapter", true) ?: true
        val toc = if (smartChapter) detectChapters(blocks) else emptyList()

        val metadata = BookMetadata(title = title, encoding = result.charset)
        return ParseResult(blocks, metadata, toc)
    }

    /**
     * 智能检测章节标题。
     * 匹配模式：
     *   第 N 章 / 第 N 节    (阿拉伯数字)
     *   第 CN 章 / 第 CN 节  (中文数字)
     *   第 N 回 / 第 N 卷    (小说惯用语)
     *   第 N 部分 / Part N   (混合格式)
     *
     * 每个匹配行会同时创建为 Heading 块（替换原 Paragraph）和 TocEntry。
     */
    private fun detectChapters(blocks: MutableList<ContentBlock>): List<TocEntry> {
        val toc = mutableListOf<TocEntry>()

        // 主正则：匹配 "第...（章|节|回|卷|部|部分|篇|集|幕|场）"
        val chapterRegex = Regex(
            """^\s*第\s*([0-9零一二三四五六七八九十百千万]+)\s*([章节回卷部篇集幕场]|部分)\s*[：:]?\s*(.*)$"""
        )

        for (i in blocks.indices) {
            val block = blocks[i]
            if (block !is ContentBlock.Paragraph) continue

            val match = chapterRegex.find(block.text.trim())
            if (match != null) {
                val numStr = match.groupValues[1]
                val unit = match.groupValues[2]
                val rest = match.groupValues[3].trim()
                val chapterNum = parseNumber(numStr)

                // 构建标题文本
                val headingText = if (rest.isNotEmpty()) {
                    "第${numStr}${unit} ${rest}"
                } else {
                    "第${numStr}${unit}"
                }

                // 替换原 Paragraph 为 Heading
                val level = if (unit == "部分" || unit == "卷" || unit == "篇") 1
                    else if (unit == "节") 3
                    else 2
                blocks[i] = ContentBlock.Heading(headingText, level)
                toc.add(TocEntry(headingText, i, level))
            }
        }

        return toc
    }

    /** 解析中文/阿拉伯数字，返回整数（用于排序/比较，目前仅用于验证有效性） */
    private fun parseNumber(s: String): Int {
        // 纯阿拉伯数字
        val arabic = s.toIntOrNull()
        if (arabic != null) return arabic

        // 中文数字
        var result = 0
        var section = 0
        for (ch in s) {
            val digit = cnDigits[ch]
            if (digit != null) {
                section = digit
                continue
            }
            val unit = cnUnits[ch]
            if (unit != null) {
                section = if (section == 0) 1 else section
                result += section * unit
                section = 0
                continue
            }
            // 非中文数字字符，跳过
        }
        result += section
        return if (result > 0) result else -1
    }
}