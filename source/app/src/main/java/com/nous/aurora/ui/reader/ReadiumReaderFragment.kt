package com.nous.aurora.ui.reader

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nous.aurora.AuroraApp
import com.nous.aurora.R
import com.nous.aurora.data.model.BookAnnotation
import com.nous.aurora.data.model.BookFormat
import com.nous.aurora.data.model.Bookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.adapter.pdfium.navigator.PdfiumDefaults
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.epub.EpubDefaults
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.toUrl
import java.io.File

@OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
class ReadiumReaderFragment : Fragment(), ReaderFragmentContract,
    EpubNavigatorFragment.Listener, PdfNavigatorFragment.Listener {

    private var bookId: Long = 0
    private var bookPath: String = ""
    private var format: BookFormat = BookFormat.UNKNOWN

    private lateinit var publication: Publication
    private lateinit var navigator: VisualNavigator
    private val db get() = AuroraApp.instance.db

    private lateinit var container: FrameLayout
    private lateinit var progressBar: ProgressBar
    private var _currentLocatorJson = ""
    private var rootView: FrameLayout? = null

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastLinkClickTime = 0L
    private var touchDownTime = 0L
    private var touchDownX = 0f
    private var touchDownY = 0f

    var onTapCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = arguments?.getLong(ARG_BOOK_ID) ?: 0
        bookPath = arguments?.getString(ARG_BOOK_PATH) ?: ""
        format = arguments?.getString(ARG_FORMAT)
            ?.let { runCatching { BookFormat.valueOf(it) }.getOrDefault(BookFormat.UNKNOWN) }
            ?: BookFormat.UNKNOWN
    }

    override fun onCreateView(
        inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        container = FrameLayout(requireContext()).apply { id = View.generateViewId() }
        progressBar = ProgressBar(requireContext())
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.CENTER
        progressBar.layoutParams = lp

        val rv = object : FrameLayout(requireContext()) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownTime = System.currentTimeMillis()
                        touchDownX = ev.x; touchDownY = ev.y
                    }
                    MotionEvent.ACTION_UP -> {
                        val dt = System.currentTimeMillis() - touchDownTime
                        val dx = Math.abs(ev.x - touchDownX)
                        val dy = Math.abs(ev.y - touchDownY)
                        if (dt < 300 && dx < 20f && dy < 20f) {
                            postDelayed({
                                if (System.currentTimeMillis() - lastLinkClickTime > 400) {
                                    onTapCallback?.invoke()
                                }
                            }, 250L)
                        }
                    }
                }
                return false
            }
            override fun performClick(): Boolean {
                postDelayed({
                    if (System.currentTimeMillis() - lastLinkClickTime > 400) {
                        onTapCallback?.invoke()
                    }
                }, 250L)
                return true
            }
        }
        rv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        rootView = rv

        ViewCompat.setImportantForAccessibility(rv, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_YES)
        rv.contentDescription = "阅读区域，双击弹出阅读菜单"

        ViewCompat.replaceAccessibilityAction(
            rv, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            "弹出阅读菜单"
        ) { _, _ ->
            handler.postDelayed({
                if (System.currentTimeMillis() - lastLinkClickTime > 400) {
                    onTapCallback?.invoke()
                }
            }, 250L)
            true
        }

        rv.apply {
            addView(container, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(progressBar)
        }
        return rv
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lifecycleScope.launch { loadPublication() }
    }

    private suspend fun loadPublication() {
        val file = File(bookPath)
        if (!file.exists()) { showError("文件不存在"); return }
        val asset = AuroraApp.instance.assetRetriever.retrieve(file).getOrNull()
        if (asset == null) { showError("无法读取文件"); return }
        val pub = AuroraApp.instance.publicationOpener.open(asset, "aurora", false).getOrNull()
        if (pub == null) { showError("无法解析文档"); return }
        publication = pub

        val initialLocator = runCatching {
            val book = db.getBookById(bookId)
            book?.locatorJson?.takeIf { it.isNotEmpty() }?.let { Locator.fromJSON(JSONObject(it)) }
        }.getOrNull()

        withContext(Dispatchers.Main) {
            setupNavigator(initialLocator)
            progressBar.visibility = View.GONE
            observeProgress()
            applyBookmarkDecorations()
            optimizeInternalWebViews()
        }
    }

    private fun optimizeInternalWebViews() {
        container.postDelayed({
            findWebViews(container).forEach { wv ->
                wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                wv.settings.apply {
                    allowFileAccess = true
                    blockNetworkLoads = true
                }
            }
        }, 500)
    }

    private fun findWebViews(view: View): List<WebView> {
        val result = mutableListOf<WebView>()
        if (view is WebView) result.add(view)
        else if (view is ViewGroup) for (i in 0 until view.childCount) result.addAll(findWebViews(view.getChildAt(i)))
        return result
    }

    private fun setupNavigator(initialLocator: Locator?) {
        val fm = childFragmentManager
        when (format) {
            BookFormat.EPUB -> {
                val factory = EpubNavigatorFactory(
                    publication,
                    EpubNavigatorFactory.Configuration(defaults = EpubDefaults(pageMargins = 1.2))
                )
                fm.fragmentFactory = factory.createFragmentFactory(initialLocator, null, EpubPreferences(), this)
                val frag = fm.fragmentFactory.instantiate(requireActivity().classLoader, EpubNavigatorFragment::class.java.name)
                fm.commitNow { add(container.id, frag, NAV_TAG) }
                navigator = fm.findFragmentByTag(NAV_TAG) as VisualNavigator
            }
            BookFormat.PDF -> {
                val engineProvider = PdfiumEngineProvider(PdfiumDefaults())
                val factory = PdfNavigatorFactory(publication, engineProvider)
                fm.fragmentFactory = factory.createFragmentFactory(initialLocator, PdfiumPreferences(), this)
                val frag = fm.fragmentFactory.instantiate(requireActivity().classLoader, "org.readium.r2.navigator.pdf.PdfNavigatorFragment")
                fm.commitNow { add(container.id, frag, NAV_TAG) }
                navigator = fm.findFragmentByTag(NAV_TAG) as VisualNavigator
            }
            else -> throw IllegalStateException("Unsupported: $format")
        }
    }

    private fun observeProgress() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                navigator.currentLocator.collect { locator ->
                    val json = locator.toJSON().toString()
                    _currentLocatorJson = json
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.updateReadingProgress(bookId, 0, locatorJson = json)
                    }
                }
            }
        }
    }

    private fun applyBookmarkDecorations() {
        lifecycleScope.launch(Dispatchers.IO) {
            val bookmarks = db.getBookmarks(bookId).filter { it.locatorJson.isNotEmpty() }
            if (bookmarks.isEmpty()) return@launch
            val decorations = bookmarks.mapNotNull { bm ->
                parseLocator(bm.locatorJson)?.let { loc ->
                    Decoration("bookmark-${bm.id}", loc, Decoration.Style.Highlight(BOOKMARK_TINT, isActive = false))
                }
            }
            withContext(Dispatchers.Main) {
                (navigator as? DecorableNavigator)?.applyDecorations(decorations, "bookmarks")
            }
        }
    }

    // ═══════════════════════════════════════
    //  TOC 跳转：用 readingOrder 计算 progression
    // ═══════════════════════════════════════

    override fun goToLocation(locationJson: String) {
        val loc = parseLocator(locationJson)
        if (loc != null) {
            // 尝试直接用 locator 跳转
            navigator.go(loc)
        } else {
            // 回退：从 tocIndex 算比例
            val index = runCatching { JSONObject(locationJson).optInt("tocIndex", -1) }.getOrDefault(-1)
            if (index >= 0) {
                val toc = publication.tableOfContents
                if (index < toc.size) {
                    val href = toc[index].url().toString()
                    val progress = findReadingOrderProgress(href)
                    val locator = Locator(
                        href = toc[index].url(),
                        mediaType = MediaType.BINARY,
                        title = toc[index].title ?: "",
                        locations = Locator.Locations(totalProgression = progress)
                    )
                    navigator.go(locator)
                }
            }
        }
        refreshAccessibility()
    }

    override fun goToProgress(percent: Int) {
        val progression = percent / 100.0
        val readingOrder = publication.readingOrder
        if (readingOrder.isNotEmpty()) {
            val index = ((readingOrder.size - 1) * progression).toInt().coerceIn(0, readingOrder.size - 1)
            val locator = Locator(
                href = readingOrder[index].url(),
                mediaType = MediaType.BINARY,
                title = "",
                locations = Locator.Locations(totalProgression = progression)
            )
            navigator.go(locator)
        }
        refreshAccessibility()
    }

    /** 在 readingOrder 中查找 href，返回 0~1 的比例 */
    private fun findReadingOrderProgress(href: String): Double {
        val readingOrder = publication.readingOrder
        if (readingOrder.isEmpty()) return 0.0
        val idx = readingOrder.indexOfFirst { it.url().toString() == href }
        if (idx >= 0) {
            return (idx.toDouble() / readingOrder.size).coerceIn(0.0, 1.0)
        }
        // 模糊匹配：比较文件名
        val fileName = href.substringAfterLast('/').substringBefore('#')
        val idx2 = readingOrder.indexOfFirst {
            it.url().toString().contains(fileName)
        }
        if (idx2 >= 0) {
            return (idx2.toDouble() / readingOrder.size).coerceIn(0.0, 1.0)
        }
        return 0.0
    }

    private fun refreshAccessibility() {
        rootView?.postDelayed({
            rootView?.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            optimizeInternalWebViews()
        }, 800)
    }

    override fun getCurrentLocation(): String =
        _currentLocatorJson.ifEmpty { navigator.currentLocator.value.toJSON().toString() }

    override fun getProgressPercent(): Int =
        ((navigator.currentLocator.value.locations.totalProgression ?: 0.0) * 100).toInt().coerceIn(0, 100)

    override fun getTableOfContents(): List<ReaderFragmentContract.TocItem> =
        publication.tableOfContents.mapIndexed { idx, link ->
            // 保存 tocIndex 用于回退跳转
            val locatorJson = JSONObject()
                .put("href", link.url().toString())
                .put("tocIndex", idx)
                .put("title", link.title ?: "")
                .toString()
            ReaderFragmentContract.TocItem(link.title ?: "无标题", locatorJson, 0)
        }

    override fun getTableOfContentsTree(): List<ReaderFragmentContract.TocTreeItem> =
        publication.tableOfContents.mapIndexed { idx, link ->
            convertToTocTree(link, idx, 0)
        }

    private fun convertToTocTree(
        link: org.readium.r2.shared.publication.Link, tocIndex: Int, depth: Int
    ): ReaderFragmentContract.TocTreeItem {
        val locatorJson = JSONObject()
            .put("href", link.url().toString())
            .put("tocIndex", tocIndex)
            .put("title", link.title ?: "")
            .toString()
        return ReaderFragmentContract.TocTreeItem(
            title = link.title ?: "无标题",
            locationJson = locatorJson,
            depth = depth,
            children = link.children.map { convertToTocTree(it, tocIndex, depth + 1) }
        )
    }

    override suspend fun search(query: String): List<ReaderFragmentContract.SearchResult> = emptyList()

    override fun addBookmark() {
        val json = getCurrentLocation()
        lifecycleScope.launch(Dispatchers.IO) {
            db.addBookmark(Bookmark(bookId = bookId, paragraphIndex = 0, locatorJson = json))
        }
    }

    override suspend fun getBookmarks(): List<Bookmark> = db.getBookmarks(bookId)

    override fun addAnnotation(text: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.addAnnotation(BookAnnotation(bookId = bookId, paragraphIndex = 0, text = text, locatorJson = getCurrentLocation()))
        }
    }

    override suspend fun getAnnotations(): List<BookAnnotation> = db.getAllAnnotations(bookId)

    override fun shouldFollowInternalLink(
        link: org.readium.r2.shared.publication.Link,
        context: org.readium.r2.navigator.HyperlinkNavigator.LinkContext?
    ): Boolean {
        lastLinkClickTime = System.currentTimeMillis()
        return true
    }

    override fun onExternalLinkActivated(url: org.readium.r2.shared.util.AbsoluteUrl) {
        lastLinkClickTime = System.currentTimeMillis()
    }

    override fun goBackFromFootnote(): Boolean = false

    private fun showError(msg: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseLocator(json: String): Locator? =
        runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()

    companion object {
        private const val ARG_BOOK_ID = "book_id"
        private const val ARG_BOOK_PATH = "book_path"
        private const val ARG_FORMAT = "format"
        private const val NAV_TAG = "readium_navigator"
        private const val BOOKMARK_TINT = 0xFF4CAF50.toInt()

        fun newInstance(bookId: Long, bookPath: String, format: BookFormat) =
            ReadiumReaderFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_BOOK_ID, bookId)
                    putString(ARG_BOOK_PATH, bookPath)
                    putString(ARG_FORMAT, format.name)
                }
            }
    }
}
