package com.nous.aurora.data.parser

import android.content.Context
import android.content.SharedPreferences
import com.nous.aurora.data.model.BookFormat
import java.io.File

object ParserFactory {
    private var _tempDir: File? = null
    private var _prefs: SharedPreferences? = null

    fun setTempDir(dir: File) { _tempDir = dir }

    /** 注入 SharedPreferences，使 TxtParser 可读取智能分章节设置 */
    fun setPreferences(prefs: SharedPreferences) { _prefs = prefs }

    fun getParser(format: BookFormat): BookParser? = when (format) {
        BookFormat.EPUB -> EpubParser()
        BookFormat.TXT -> TxtParser().apply { prefs = _prefs }
        BookFormat.MARKDOWN -> MarkdownParser()
        BookFormat.FB2 -> Fb2Parser()
        BookFormat.MOBI -> MobiParser(_tempDir)
        BookFormat.AZW -> MobiParser(_tempDir)
        BookFormat.AZW3 -> MobiParser(_tempDir)
        else -> null
    }

    val supportedExtensions: Set<String> = setOf("epub", "txt", "md", "markdown", "fb2", "mobi", "azw", "azw3")
}