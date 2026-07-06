package com.nous.aurora.ui.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nous.aurora.AuroraApp
import com.nous.aurora.R
import com.nous.aurora.data.model.Book
import com.nous.aurora.data.model.BookAnnotation
import com.nous.aurora.data.model.BookFormat
import com.nous.aurora.data.model.Bookmark
import com.nous.aurora.data.model.ContentBlock
import com.nous.aurora.data.parser.ParserFactory
import com.nous.aurora.data.parser.ParseResult
import com.nous.aurora.data.parser.TocEntry
import com.nous.aurora.util.AccessibilityUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class NativeReaderFragment : Fragment(), ReaderFragmentContract {

    private var bookId: Long = 0
    private var bookPath: String = ""
    private var format: BookFormat = BookFormat.UNKNOWN
    private var book: Book? = null

    private var blocks: List<ContentBlock> = emptyList()
    private var toc: List<TocEntry> = emptyList()
    private var linkMap: Map<String, Int> = emptyMap()
    private var currentParagraphIndex = 0
    private var announceParagraphNumber = false
    private val footnoteStack = ArrayDeque<Int>()
    private var isRestoringPosition = false

    private lateinit var recyclerView: RecyclerView
    private var adapter: ReaderAdapter? = null
    private var layoutManager: LinearLayoutManager? = null
    private val db get() = AuroraApp.instance.db

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = arguments?.getLong(ARG_BOOK_ID) ?: 0
        bookPath = arguments?.getString(ARG_BOOK_PATH) ?: ""
        format = arguments?.getString(ARG_FORMAT)
            ?.let { runCatching { BookFormat.valueOf(it) }.getOrDefault(BookFormat.UNKNOWN) }
            ?: BookFormat.UNKNOWN
        announceParagraphNumber =
            requireActivity().getSharedPreferences("reader_prefs", android.content.Context.MODE_PRIVATE)
                .getBoolean("announce_paragraph", false)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        recyclerView = RecyclerView(requireContext()).apply {
            id = R.id.native_reader_recycler
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(false)
            setItemViewCacheSize(10)
            recycledViewPool.setMaxRecycledViews(0, 20)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        this.layoutManager = recyclerView.layoutManager as LinearLayoutManager
        return recyclerView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lifecycleScope.launch { loadContent() }
    }

    override fun onPause() {
        super.onPause()
        saveReadingPosition()
    }

    private fun saveReadingPosition() {
        if (bookId <= 0 || currentParagraphIndex < 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            db.updateReadingProgress(bookId, currentParagraphIndex)
        }
    }

    private suspend fun loadContent() {
        val result: ParseResult? = withContext(Dispatchers.IO) {
            book = db.getBookById(bookId)
            if (book == null && bookPath.isNotEmpty()) {
                val parser = ParserFactory.getParser(format)
                if (parser != null) {
                    val r = parser.parse(bookPath)
                    val b = Book(
                        filePath = bookPath,
                        title = r.metadata.title.ifBlank { File(bookPath).nameWithoutExtension },
                        author = r.metadata.author,
                        format = format,
                        totalParagraphs = r.blocks.size
                    )
                    bookId = db.insertOrUpdateBook(b)
                    book = db.getBookById(bookId)
                    r
                } else null
            } else if (book != null) {
                val parser = ParserFactory.getParser(book!!.format)
                parser?.parse(book!!.filePath)?.also {
                    db.updateTotalParagraphs(bookId, it.blocks.size)
                }
            } else null
        }

        if (result == null || result.blocks.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "无法解析该文档", Toast.LENGTH_LONG).show()
            }
            return
        }

        blocks = result.blocks
        toc = result.toc
        linkMap = result.linkMap
        currentParagraphIndex = (book?.lastParagraphIndex ?: 0).coerceIn(0, blocks.size - 1)

        val annotationCounts = withContext(Dispatchers.IO) {
            mutableMapOf<Int, Int>().apply {
                for (i in blocks.indices) {
                    val count = db.getAnnotationsForParagraph(bookId, i).size
                    if (count > 0) put(i, count)
                }
            }
        }

        adapter = ReaderAdapter(
            blocks, annotationCounts,
            { block, index -> onBlockTap(block, index) },
            { announceParagraphNumber }
        )

        recyclerView.adapter = adapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var lastSaved = -1
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (isRestoringPosition) return
                layoutManager?.findFirstVisibleItemPosition()?.let { pos ->
                    if (pos >= 0 && pos != currentParagraphIndex) {
                        currentParagraphIndex = pos
                        if (announceParagraphNumber) {
                            AccessibilityUtil.announce(requireActivity(), "第${pos + 1}段")
                        }
                    }
                }
                if (currentParagraphIndex != lastSaved && currentParagraphIndex >= 0) {
                    lastSaved = currentParagraphIndex
                    saveReadingPosition()
                }
            }
        })

        // 恢复阅读位置：滚动到目标段落，然后把读屏焦点放到那个段落上
        if (currentParagraphIndex > 0) {
            isRestoringPosition = true
            recyclerView.post {
                layoutManager?.scrollToPositionWithOffset(currentParagraphIndex, 0)
                // 延迟等待 RecyclerView 完成布局，然后对目标 view 请求无障碍焦点
                recyclerView.postDelayed({
                    val targetView = layoutManager?.findViewByPosition(currentParagraphIndex)
                    if (targetView != null) {
                        AccessibilityUtil.requestFocus(targetView)
                    }
                    isRestoringPosition = false
                }, 500)
            }
        }
    }

    private fun onBlockTap(block: ContentBlock, index: Int) {
        currentParagraphIndex = index
        when (block) {
            is ContentBlock.Link -> handleLinkTap(block)
            else -> {
                // 点击任意正文段落弹出阅读菜单
                (parentFragment as? MenuListener)?.onShowReadingMenu(index)
                    ?: (activity as? MenuListener)?.onShowReadingMenu(index)
            }
        }
    }

    private fun handleLinkTap(link: ContentBlock.Link) {
        val href = link.href

        linkMap[href]?.let {
            if (it in blocks.indices) {
                footnoteStack.addLast(currentParagraphIndex)
                navigateToBlock(it)
                return
            }
        }

        if (href.contains("#")) {
            val filePart = href.substringBefore("#")
            val anchor = href.substringAfterLast("#")

            linkMap[href]?.let { idx ->
                if (idx in blocks.indices) { footnoteStack.addLast(currentParagraphIndex); navigateToBlock(idx); return }
            }
            linkMap["#$anchor"]?.let { idx ->
                if (idx in blocks.indices) { footnoteStack.addLast(currentParagraphIndex); navigateToBlock(idx); return }
            }
            linkMap[filePart]?.let { fileStart ->
                for (i in fileStart until blocks.size) {
                    val b = blocks[i]
                    if (b.text.contains(anchor, ignoreCase = true) ||
                        (b is ContentBlock.Heading && b.text.lowercase()
                            .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "-").trim('-') == anchor)
                    ) {
                        footnoteStack.addLast(currentParagraphIndex); navigateToBlock(i); return
                    }
                }
                footnoteStack.addLast(currentParagraphIndex); navigateToBlock(fileStart); return
            }

            val slug = anchor.lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "-").trim('-')
            linkMap["#$slug"]?.let { footnoteStack.addLast(currentParagraphIndex); navigateToBlock(it); return }

            val linkNum = link.text.trim().removeSuffix(".")
            if (linkNum.isNotEmpty()) {
                for (i in blocks.indices) {
                    val t = blocks[i].text.trim()
                    if (t.startsWith("$linkNum.") || t.startsWith("$linkNum ") || t.startsWith("$linkNum\u3000")) {
                        footnoteStack.addLast(currentParagraphIndex); navigateToBlock(i); return
                    }
                }
            }
        }

        val tocEntry = toc.find { it.title == link.text }
        if (tocEntry != null && tocEntry.paragraphIndex in blocks.indices) {
            footnoteStack.addLast(currentParagraphIndex); navigateToBlock(tocEntry.paragraphIndex); return
        }

        Toast.makeText(requireContext(), "外部链接: $href", Toast.LENGTH_SHORT).show()
    }

    override fun goBackFromFootnote(): Boolean {
        if (footnoteStack.isEmpty()) return false
        navigateToBlock(footnoteStack.removeLast())
        return true
    }

    override fun getCurrentLocation(): String {
        return JSONObject().put("index", currentParagraphIndex).toString()
    }

    override fun goToLocation(locationJson: String) {
        val index = try { JSONObject(locationJson).getInt("index") } catch (_: Exception) { return }
        navigateToBlock(index)
    }

    override fun goToProgress(percent: Int) {
        if (blocks.isEmpty()) return
        val index = ((percent / 100.0) * (blocks.size - 1)).toInt().coerceIn(0, blocks.size - 1)
        navigateToBlock(index)
    }

    override fun getProgressPercent(): Int {
        if (blocks.isEmpty()) return 0
        return ((currentParagraphIndex + 1) * 100 / blocks.size).coerceIn(0, 100)
    }

    override fun getTableOfContents(): List<ReaderFragmentContract.TocItem> {
        return toc.map { entry ->
            ReaderFragmentContract.TocItem(
                title = entry.title,
                locationJson = JSONObject().put("index", entry.paragraphIndex).toString(),
                depth = entry.level
            )
        }
    }

    override fun getTableOfContentsTree(): List<ReaderFragmentContract.TocTreeItem> {
        if (toc.isEmpty()) return emptyList()
        if (toc.any { it.children.isNotEmpty() }) {
            return toc.map { convertTocEntryToTree(it, 0) }
        }
        return ReaderFragmentContract.buildTocTreeFromFlat(getTableOfContents())
    }

    private fun convertTocEntryToTree(entry: TocEntry, depth: Int): ReaderFragmentContract.TocTreeItem {
        return ReaderFragmentContract.TocTreeItem(
            title = entry.title,
            locationJson = JSONObject().put("index", entry.paragraphIndex).toString(),
            depth = depth,
            children = entry.children.map { convertTocEntryToTree(it, depth + 1) }
        )
    }

    override suspend fun search(query: String): List<ReaderFragmentContract.SearchResult> {
        return blocks.mapIndexedNotNull { index, block ->
            if (block.text.contains(query, ignoreCase = true)) {
                ReaderFragmentContract.SearchResult(
                    preview = "第${index + 1}段: ${block.text.take(50).replace("\n", " ")}...",
                    locationJson = JSONObject().put("index", index).toString(),
                    title = null
                )
            } else null
        }
    }

    override fun addBookmark() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (!db.hasBookmark(bookId, currentParagraphIndex)) {
                db.addBookmark(Bookmark(bookId = bookId, paragraphIndex = currentParagraphIndex))
            }
        }
    }

    override suspend fun getBookmarks(): List<Bookmark> = db.getBookmarks(bookId)

    override fun addAnnotation(text: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.addAnnotation(BookAnnotation(bookId = bookId, paragraphIndex = currentParagraphIndex, text = text))
        }
    }

    override suspend fun getAnnotations(): List<BookAnnotation> = db.getAllAnnotations(bookId)

    private fun navigateToBlock(index: Int) {
        currentParagraphIndex = index.coerceIn(0, blocks.size - 1)
        saveReadingPosition()

        val targetBlock = blocks.getOrNull(currentParagraphIndex)
        val announceText = when (targetBlock) {
            is ContentBlock.Heading -> "已跳转到：${targetBlock.text}"
            else -> "已跳转到第${currentParagraphIndex + 1}段"
        }

        recyclerView.post {
            layoutManager?.scrollToPositionWithOffset(currentParagraphIndex, 0)

            recyclerView.postDelayed({
                if (!isAdded) return@postDelayed

                // 精确地把无障碍焦点设置到目标段落视图上
                val targetView = layoutManager?.findViewByPosition(currentParagraphIndex)
                if (targetView != null) {
                    AccessibilityUtil.requestFocus(targetView)
                }
                AccessibilityUtil.announce(requireActivity(), announceText)
            }, 400)
        }
    }

    interface MenuListener {
        fun onShowReadingMenu(paragraphIndex: Int)
    }

    companion object {
        private const val ARG_BOOK_ID = "book_id"
        private const val ARG_BOOK_PATH = "book_path"
        private const val ARG_FORMAT = "format"

        fun newInstance(bookId: Long, bookPath: String, format: BookFormat): NativeReaderFragment {
            return NativeReaderFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_BOOK_ID, bookId)
                    putString(ARG_BOOK_PATH, bookPath)
                    putString(ARG_FORMAT, format.name)
                }
            }
        }
    }
}
