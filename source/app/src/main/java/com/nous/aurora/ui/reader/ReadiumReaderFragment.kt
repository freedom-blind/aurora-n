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
        // 硬件加速：减少 WebView 渲染卡顿
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
            // 优化所有内部 WebView 的渲染和无障碍
            optimizeInternalWebViews()
        }
    }

    /**
     * 递归查找所有 WebView 并优化设置：
     * - 开启硬件加速减少卡顿
     * - 设置合适的缓存模式
     * - 延迟后刷新无障碍树，确保跳转后内容完整可见
     */
    private fun optimizeInternalWebViews() {
        container.postDelayed({
            findWebViews(container).forEach { wv ->
                wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                wv.settings.apply {
                    // 减少渲染开销
                    setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
                    // 允许文件访问（本地 EPUB 资源）
                    allowFileAccess = true
                    // 不要自动加载图片以外的资源
                    blockNetworkLoads = true
                }
            }
        }, 500)
    }

    private fun findWebViews(view: View): List<WebView> {
        val result = mutableListOf<WebView>()
        if (view is WebView) {
            result.add(view)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                result.addAll(findWebViews(view.getChildAt(i)))
            }
        }
        return result
    }

    private fun setupNavigator(initialLocator: Locator?) {
        val fm = childFragmentManager
        when (format) {
            BookFormat.EPUB -> {
                val factory = EpubNavigatorFactory(
                    publication,
                    EpubNavigatorFactory.Configuration(
                        defaults = EpubDefaults(pageMargins = 1.2)
                    )
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

    // ── 跳转后刷新无障碍，修复读屏只能读到部分内容的问题 ──

    override fun goToLocation(locationJson: String) {
        parseLocator(locationJson)?.let { loc ->
            navigator.go(loc)
            // WebView 跳转后内容异步加载，延迟刷新无障碍树
            rootView?.postDelayed({
                rootView?.sendAccessibilityEvent(
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                )
                // 再找一次 WebView，确保设置生效
                optimizeInternalWebViews()
            }, 800)
        }
    }

    override fun goToProgress(percent: Int) {
        val progression = percent / 100.0
        val href = publication.readingOrder.firstOrNull()?.url() ?: return
        val locator = Locator(href, MediaType.BINARY, "", Locator.Locations(totalProgression = progression), Locator.Text())
        navigator.go(locator)
        rootView?.postDelayed({
            rootView?.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }, 800)
    }

    override fun getCurrentLocation(): String =
        _currentLocatorJson.ifEmpty { navigator.currentLocator.value.toJSON().toString() }

    override fun getProgressPercent(): Int =
        ((navigator.currentLocator.value.locations.totalProgression ?: 0.0) * 100).toInt().coerceIn(0, 100)

    override fun getTableOfContents(): List<ReaderFragmentContract.TocItem> =
        publication.tableOfContents.flatMap { flattenToc(it, 0) }

    override fun getTableOfContentsTree(): List<ReaderFragmentContract.TocTreeItem> =
        publication.tableOfContents.map { convertToTocTree(it, 0) }

    private fun convertToTocTree(
        link: org.readium.r2.shared.publication.Link, depth: Int
    ): ReaderFragmentContract.TocTreeItem {
        val title = link.title?.takeIf { it.isNotBlank() } ?: "无标题"
        val locatorJson = Locator(link.url(), MediaType.BINARY, title, Locator.Locations(), Locator.Text()).toJSON().toString()
        return ReaderFragmentContract.TocTreeItem(
            title = title, locationJson = locatorJson, depth = depth,
            children = link.children.map { convertToTocTree(it, depth + 1) }
        )
    }

    private fun flattenToc(
        link: org.readium.r2.shared.publication.Link, depth: Int
    ): List<ReaderFragmentContract.TocItem> {
        val locator = Locator(link.url(), MediaType.BINARY, link.title ?: "无标题", Locator.Locations(), Locator.Text())
        return listOf(ReaderFragmentContract.TocItem(link.title ?: "无标题", locator.toJSON().toString(), depth)) +
                link.children.flatMap { flattenToc(it, depth + 1) }
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
