package com.nous.aurora.ui.bookshelf

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SearchView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.nous.aurora.AuroraApp
import com.nous.aurora.data.model.Book
import com.nous.aurora.data.model.BookFormat
import com.nous.aurora.data.parser.ParserFactory
import com.nous.aurora.databinding.ActivityBookshelfBinding
import com.nous.aurora.ui.reader.UniversalReaderActivity
import com.nous.aurora.ui.settings.SettingsActivity
import com.nous.aurora.util.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BookshelfActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookshelfBinding
    private val bookAdapter = BookAdapter(
        onBookClick = { book -> openBook(book) },
        onBookLongClick = { book -> removeFromShelf(book) }
    )
    private val fileAdapter = FileBrowserAdapter(
        onDirClick = { entry -> navigateToDir(entry.file) },
        onFileClick = { entry -> importFileToShelf(entry.file) },
        onFileLongClick = { entry -> importFileToShelf(entry.file) }
    )
    private val db get() = AuroraApp.instance.db
    private var allBooks: List<Book> = emptyList()
    private var sortField = "last_read_at"
    private var sortAscending = false
    private var isImportMode = false
    private var currentDir: File = Environment.getExternalStorageDirectory()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleManager.applyLocale(this)
        binding = ActivityBookshelfBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "书架"

        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        setupSortSpinner()

        binding.btnImport.setOnClickListener { startImport() }
        binding.btnBackShelf.setOnClickListener { exitImportMode() }
        binding.btnUpDir.setOnClickListener { navigateUp() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnGetBooks.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://z-lib.today")))
        }

        showBookshelf()
        handleIntent(intent)
    }

    override fun onBackPressed() {
        if (isImportMode && currentDir != Environment.getExternalStorageDirectory()) {
            navigateUp()
        } else if (isImportMode) {
            exitImportMode()
        } else {
            super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.path?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    lifecycleScope.launch {
                        val book = importBookToShelfInternal(file)
                        if (book != null) openBook(book)
                    }
                }
            }
        }
    }

    // ── Bookshelf ──

    private fun showBookshelf() {
        isImportMode = false
        binding.toolbarFiles.visibility = View.GONE
        binding.toolbarBooks.visibility = View.VISIBLE
        binding.recyclerView.adapter = bookAdapter
        loadBooks()
    }

    private fun loadBooks() {
        lifecycleScope.launch {
            allBooks = withContext(Dispatchers.IO) {
                db.getAllBooks(sortField, sortAscending, favoritesOnly = true)
            }
            bookAdapter.submitList(allBooks)
            binding.tvEmpty.visibility = if (allBooks.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun removeFromShelf(book: Book) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.setFavorite(book.id, false)
            withContext(Dispatchers.Main) {
                loadBooks()
                Toast.makeText(this@BookshelfActivity, "已从书架移除", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openBook(book: Book) {
        val intent = Intent(this, UniversalReaderActivity::class.java).apply {
            putExtra("book_id", book.id)
            putExtra("book_path", book.filePath)
            putExtra("format", book.format.name)
        }
        startActivity(intent)
    }

    // ── Import ──

    private fun startImport() {
        checkStoragePermission {
            isImportMode = true
            binding.toolbarFiles.visibility = View.VISIBLE
            binding.toolbarBooks.visibility = View.GONE
            binding.recyclerView.adapter = fileAdapter
            currentDir = Environment.getExternalStorageDirectory()
            loadCurrentDirectory()
        }
    }

    private fun exitImportMode() {
        showBookshelf()
    }

    private fun importFileToShelf(file: File) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val book = withContext(Dispatchers.IO) { importBookToShelfInternal(file) }
            binding.progressBar.visibility = View.GONE
            if (book != null) {
                db.setFavorite(book.id, true)
                Toast.makeText(this@BookshelfActivity, "已添加到书架: ${book.title}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@BookshelfActivity, "无法导入此文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun importBookToShelfInternal(file: File): Book? = withContext(Dispatchers.IO) {
        val ext = file.extension.lowercase()
        val format = BookFormat.fromExtension(ext)
        if (format == BookFormat.UNKNOWN) return@withContext null

        val book = try {
            if (format.isReadiumSupported) {
                val asset = AuroraApp.instance.assetRetriever.retrieve(file).getOrNull() ?: return@withContext null
                val pub = AuroraApp.instance.publicationOpener.open(asset, "aurora", false).getOrNull() ?: return@withContext null
                Book(
                    filePath = file.absolutePath,
                    title = pub.metadata.title.orEmpty().ifBlank { file.nameWithoutExtension },
                    author = pub.metadata.authors.joinToString(", ") { it.name.orEmpty() },
                    format = format,
                    fileModifiedAt = file.lastModified()
                )
            } else {
                val parser = ParserFactory.getParser(format) ?: return@withContext null
                val result = parser.parse(file.absolutePath)
                Book(
                    filePath = file.absolutePath,
                    title = result.metadata.title.ifBlank { file.nameWithoutExtension },
                    author = result.metadata.author,
                    format = format,
                    totalParagraphs = result.blocks.size,
                    fileModifiedAt = file.lastModified()
                )
            }
        } catch (_: Exception) {
            Book(
                filePath = file.absolutePath,
                title = file.nameWithoutExtension,
                format = format,
                fileModifiedAt = file.lastModified()
            )
        }

        val id = db.insertOrUpdateBook(book)
        book.copy(id = id)
    }

    // ── File browser ──

    private fun loadCurrentDirectory() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val entries = withContext(Dispatchers.IO) {
                val files = currentDir.listFiles()?.toList() ?: emptyList()
                val dirs = files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                val supported = files.filter { FileEntry.isSupportedFile(it) }.sortedBy { it.name.lowercase() }
                dirs.map { FileEntry.fromFile(it) } + supported.map { FileEntry.fromFile(it) }
            }
            binding.progressBar.visibility = View.GONE
            binding.tvCurrentPath.text = currentDir.absolutePath
            binding.btnUpDir.isEnabled = currentDir.parentFile != null
            fileAdapter.submitList(entries)
            binding.tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            if (entries.isEmpty()) binding.tvEmpty.text = "此目录为空或没有支持的电子书"
        }
    }

    private fun navigateToDir(dir: File) {
        currentDir = dir
        loadCurrentDirectory()
    }

    private fun navigateUp() {
        val root = File("/storage/emulated/0")
        currentDir.parentFile?.let { parent ->
            if (parent.absolutePath.length < root.absolutePath.length) return
            currentDir = parent
            loadCurrentDirectory()
        }
    }

    // ── Sort ──

    private fun setupSortSpinner() {
        val sortOptions = arrayOf("最近阅读", "书名", "作者", "修改时间")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sortOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSort.adapter = adapter
        binding.spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                sortField = when (pos) {
                    0 -> "last_read_at"; 1 -> "title"; 2 -> "author"; 3 -> "file_modified_at"
                    else -> "last_read_at"
                }
                if (!isImportMode) loadBooks()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.btnSortOrder.setOnClickListener {
            sortAscending = !sortAscending
            binding.btnSortOrder.text = if (sortAscending) "升序" else "降序"
            if (!isImportMode) loadBooks()
        }
    }

    // ── Permission ──

    private fun checkStoragePermission(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                onGranted()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("需要文件访问权限")
                    .setMessage("Aurora 需要访问存储空间才能浏览电子书文件。请授予所有文件访问权限。")
                    .setPositiveButton("去设置") { _, _ ->
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
                onGranted()
            } else {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 100)
            }
        }
    }

    // ── Search ──

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(com.nous.aurora.R.menu.bookshelf_menu, menu)
        val searchItem = menu.findItem(com.nous.aurora.R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = "搜索书名、作者..."
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let { searchBooks(it) }; return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText.isNullOrBlank()) loadBooks() else searchBooks(newText)
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            com.nous.aurora.R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java)); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun searchBooks(query: String) {
        lifecycleScope.launch {
            allBooks = withContext(Dispatchers.IO) { db.searchBooks(query) }
            bookAdapter.submitList(allBooks)
        }
    }
}
