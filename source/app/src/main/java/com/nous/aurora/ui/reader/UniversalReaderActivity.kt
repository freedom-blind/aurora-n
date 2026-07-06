package com.nous.aurora.ui.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.nous.aurora.AuroraApp
import com.nous.aurora.R
import com.nous.aurora.data.model.Book
import com.nous.aurora.data.model.BookFormat
import com.nous.aurora.data.parser.TxtParser
import com.nous.aurora.data.parser.ParserFactory
import com.nous.aurora.util.EncodingUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 统一阅读器 Activity — 支持所有电子书格式。
 *
 * 对 TXT 文档提供编辑功能：
 * - 阅读菜单中出现"编辑"选项
 * - 点击后打开编辑界面，默认从当前段落开始
 * - 自动使用检测到的编码
 * - 返回时弹出是否保存更改的提示
 */
class UniversalReaderActivity : AppCompatActivity(), NativeReaderFragment.MenuListener {

    private val db get() = AuroraApp.instance.db
    private var bookId: Long = 0
    private var bookPath: String = ""
    private lateinit var format: BookFormat
    private var book: Book? = null

    private lateinit var contentContainer: View
    private lateinit var progressBar: View
    private lateinit var topToolbar: MaterialToolbar

    private var readerFragment: ReaderFragmentContract? = null

    // TXT 编辑相关
    private var txtHasChanges = false
    private var txtOriginalContent: String? = null
    private var txtEncoding: String = "UTF-8"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_universal_reader)

        bookId = intent.getLongExtra("book_id", 0)
        bookPath = intent.getStringExtra("book_path") ?: ""
        format = runCatching {
            BookFormat.valueOf(intent.getStringExtra("format") ?: BookFormat.UNKNOWN.name)
        }.getOrDefault(BookFormat.UNKNOWN)

        bindViews()
        setupToolbar()
        setupBackPressedHandler()
        loadBook()
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (format == BookFormat.TXT && txtHasChanges) {
                        showSaveChangesDialog()
                        return
                    }
                    if (readerFragment?.goBackFromFootnote() == true) {
                        return
                    }
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            })
    }

    private fun bindViews() {
        contentContainer = findViewById(R.id.content_container)
        progressBar = findViewById(R.id.progress_bar)
        topToolbar = findViewById(R.id.top_toolbar)
    }

    private fun setupToolbar() {
        topToolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
        topToolbar.setNavigationContentDescription("返回")
        topToolbar.setNavigationOnClickListener {
            if (format == BookFormat.TXT && txtHasChanges) {
                showSaveChangesDialog()
                return@setNavigationOnClickListener
            }
            if (readerFragment?.goBackFromFootnote() == true) {
                return@setNavigationOnClickListener
            }
            finish()
        }

        topToolbar.menu.clear()
        val tocMenuItem = topToolbar.menu.add(0, 1, 0, "目录")
        tocMenuItem.setIcon(android.R.drawable.ic_menu_sort_by_size)
        tocMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        tocMenuItem.title = "目录"

        topToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { showToc(); true }
                else -> false
            }
        }
    }

    private fun loadBook() {
        lifecycleScope.launch {
            book = withContext(Dispatchers.IO) { db.getBookById(bookId) }
            title = book?.title ?: "Aurora"
            topToolbar.title = title

            val fragment = if (format.isReadiumSupported) {
                ReadiumReaderFragment.newInstance(bookId, bookPath, format)
            } else {
                NativeReaderFragment.newInstance(bookId, bookPath, format)
            }

            readerFragment = fragment

            if (fragment is ReadiumReaderFragment) {
                fragment.onTapCallback = { showReadingMenu() }
            }

            supportFragmentManager.beginTransaction()
                .replace(R.id.content_container, fragment)
                .commitNow()

                        progressBar.visibility = View.GONE
            topToolbar.visibility = View.VISIBLE
            topToolbar.postDelayed({ contentContainer.requestFocus() }, 300)

            // 对新创建的 TXT 直接打开编辑
            if (format == BookFormat.TXT && intent.getBooleanExtra("open_editor", false)) {
                topToolbar.postDelayed({ showEditDialog() }, 500)
            }

            // 对 TXT 文件，读取原始内容和编码，读取原始内容和编码
            if (format == BookFormat.TXT) {
                withContext(Dispatchers.IO) {
                    val file = File(bookPath)
                    if (file.exists()) {
                        val result = EncodingUtil.readWithEncodingDetection(file)
                        txtEncoding = result.charset.split(" ")[0]
                        txtOriginalContent = result.text
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  阅读菜单
    // ════════════════════════════════════════════════════════════════

    private fun showReadingMenu() {
        val items = mutableListOf(
            "目录",
            "添加书签",
            "书签列表",
            "添加批注",
            "批注列表",
            "搜索",
            "阅读进度"
        )
        if (format == BookFormat.TXT) {
            items.add("编辑")
        }

        AlertDialog.Builder(this)
            .setTitle("阅读菜单")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showToc()
                    1 -> {
                        readerFragment?.addBookmark()
                        Toast.makeText(this, "书签已保存", Toast.LENGTH_SHORT).show()
                    }
                    2 -> showBookmarks()
                    3 -> showAddAnnotationDialog()
                    4 -> showAnnotations()
                    5 -> showSearchDialog()
                    6 -> {
                        val pct = readerFragment?.getProgressPercent() ?: 0
                        Toast.makeText(this, "当前进度 ${pct}%", Toast.LENGTH_SHORT).show()
                    }
                    7 -> {
                        if (format == BookFormat.TXT) showEditDialog()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ════════════════════════════════════════════════════════════════
    //  TXT 编辑功能 — 使用检测到的编码
    // ════════════════════════════════════════════════════════════════

    private fun showEditDialog() {
        val currentIndex = try {
            JSONObject(readerFragment?.getCurrentLocation() ?: "{}").optInt("index", 0)
        } catch (_: Exception) { 0 }

        lifecycleScope.launch {
            val fullText = withContext(Dispatchers.IO) {
                try {
                    val file = File(bookPath)
                    EncodingUtil.readWithCharset(file, txtEncoding)
                } catch (_: Exception) { "" }
            }

            val blocks = fullText.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotBlank() }
            val startBlock = currentIndex.coerceIn(0, blocks.size - 1)
            val editBlocks = blocks.drop(startBlock).take(50)
            val editText = editBlocks.joinToString("\n\n")

            val input = EditText(this@UniversalReaderActivity)
            input.setText(editText)
            input.setSelection(0)
            input.minLines = 10
            input.hint = "从当前段落开始编辑..."
            input.contentDescription = "TXT文档编辑区域"

            AlertDialog.Builder(this@UniversalReaderActivity)
                .setTitle("编辑文档（编码：$txtEncoding，从第${startBlock + 1}段）")
                .setView(input)
                .setPositiveButton("保存更改") { _, _ ->
                    saveTxtChanges(startBlock, input.text.toString())
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun saveTxtChanges(startBlock: Int, newText: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(bookPath)
                    val fullText = EncodingUtil.readWithCharset(file, txtEncoding)
                    val blocks = fullText.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotBlank() }

                    val newBlocks = newText.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotBlank() }
                    val resultBlocks = blocks.toMutableList()
                    val removeCount = minOf(newBlocks.size, resultBlocks.size - startBlock)
                    for (i in 0 until removeCount) {
                        if (startBlock < resultBlocks.size) resultBlocks.removeAt(startBlock)
                    }
                    resultBlocks.addAll(startBlock.coerceAtMost(resultBlocks.size), newBlocks)

                    val resultText = resultBlocks.joinToString("\n\n")
                    // 使用检测到的编码保存
                    file.writeText(resultText, java.nio.charset.Charset.forName(txtEncoding))

                    txtOriginalContent = resultText
                    txtHasChanges = false

                    withContext(Dispatchers.Main) {
                        val fragment = NativeReaderFragment.newInstance(bookId, bookPath, format)
                        readerFragment = fragment
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.content_container, fragment)
                            .commitNow()
                        Toast.makeText(this@UniversalReaderActivity, "更改已保存（编码：$txtEncoding）", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@UniversalReaderActivity, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showSaveChangesDialog() {
        AlertDialog.Builder(this)
            .setTitle("未保存的更改")
            .setMessage("TXT文档有未保存的更改，是否保存？")
            .setPositiveButton("保存") { _, _ ->
                txtHasChanges = false
                finish()
            }
            .setNegativeButton("不保存") { _, _ ->
                txtHasChanges = false
                finish()
            }
            .setNeutralButton("取消", null)
            .show()
    }

    // ════════════════════════════════════════════════════════════════
    //  目录
    // ════════════════════════════════════════════════════════════════

    private fun showToc() {
        val tree = readerFragment?.getTableOfContentsTree() ?: emptyList()
        if (tree.isEmpty()) {
            Toast.makeText(this, "本文档没有目录", Toast.LENGTH_SHORT).show()
            return
        }
        val sheet = ReaderBottomSheet(this, "目录")
        sheet.onTocItemClick = { locationJson ->
            readerFragment?.goToLocation(locationJson)
        }
        sheet.setTocItems(tree.map { convertToSheetTocItem(it) })
        sheet.show()
    }

    private fun convertToSheetTocItem(
        item: ReaderFragmentContract.TocTreeItem
    ): ReaderBottomSheet.TocTreeItem {
        return ReaderBottomSheet.TocTreeItem(
            title = item.title,
            locationJson = item.locationJson,
            depth = item.depth,
            children = item.children.map { convertToSheetTocItem(it) }
        )
    }

    // ════════════════════════════════════════════════════════════════
    //  搜索
    // ════════════════════════════════════════════════════════════════

    private fun showSearchDialog() {
        val input = EditText(this)
        input.hint = "搜索文档内容..."
        input.contentDescription = "搜索输入框"

        AlertDialog.Builder(this)
            .setTitle("文档搜索")
            .setView(input)
            .setPositiveButton("搜索") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotBlank()) performSearch(query)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performSearch(query: String) {
        lifecycleScope.launch {
            val results = readerFragment?.search(query) ?: emptyList()
            if (results.isEmpty()) {
                Toast.makeText(
                    this@UniversalReaderActivity,
                    "未找到 \"$query\"",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val sheet = ReaderBottomSheet(this@UniversalReaderActivity, "搜索结果 (${results.size})")
            sheet.onItemClick = { which ->
                readerFragment?.goToLocation(results[which].locationJson)
            }
            sheet.setItems(
                results.map {
                    ReaderBottomSheet.SheetItem(it.preview, subtitle = it.title)
                }
            )
            sheet.show()
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  书签
    // ════════════════════════════════════════════════════════════════

    private fun showBookmarks() {
        lifecycleScope.launch {
            val bookmarks = readerFragment?.getBookmarks() ?: emptyList()
            if (bookmarks.isEmpty()) {
                Toast.makeText(this@UniversalReaderActivity, "暂无书签", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val sheet = ReaderBottomSheet(this@UniversalReaderActivity, "书签 (${bookmarks.size})")
            sheet.onItemClick = { which ->
                val locator = bookmarks[which].locatorJson
                if (locator.isNotEmpty()) {
                    readerFragment?.goToLocation(locator)
                } else {
                    readerFragment?.goToLocation(
                        JSONObject().put("index", bookmarks[which].paragraphIndex).toString()
                    )
                }
            }
            sheet.setItems(
                bookmarks.mapIndexed { index, bm ->
                    val title = if (bm.locatorJson.isNotEmpty()) {
                        "书签 ${index + 1}"
                    } else {
                        "书签 ${index + 1} (第${bm.paragraphIndex + 1}段)"
                    }
                    ReaderBottomSheet.SheetItem(title = title, subtitle = "点击跳转")
                }
            )
            sheet.show()
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  批注
    // ════════════════════════════════════════════════════════════════

    private fun showAddAnnotationDialog() {
        val input = EditText(this)
        input.hint = "输入批注内容..."
        input.contentDescription = "批注输入框"

        AlertDialog.Builder(this)
            .setTitle("添加批注")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotBlank()) {
                    readerFragment?.addAnnotation(text)
                    Toast.makeText(this, "批注已保存", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAnnotations() {
        lifecycleScope.launch {
            val annotations = readerFragment?.getAnnotations() ?: emptyList()
            if (annotations.isEmpty()) {
                Toast.makeText(this@UniversalReaderActivity, "暂无批注", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val sheet = ReaderBottomSheet(this@UniversalReaderActivity, "批注 (${annotations.size})")
            sheet.onItemClick = { which ->
                val locator = annotations[which].locatorJson
                if (locator.isNotEmpty()) {
                    readerFragment?.goToLocation(locator)
                } else {
                    readerFragment?.goToLocation(
                        JSONObject().put("index", annotations[which].paragraphIndex).toString()
                    )
                }
            }
            sheet.setItems(
                annotations.map { ann ->
                    ReaderBottomSheet.SheetItem(ann.text.take(60), subtitle = "点击跳转")
                }
            )
            sheet.show()
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  NativeReaderFragment.MenuListener 实现
    // ════════════════════════════════════════════════════════════════

    override fun onShowReadingMenu(paragraphIndex: Int) {
        showReadingMenu()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}