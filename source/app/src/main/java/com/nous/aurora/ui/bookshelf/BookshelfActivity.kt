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
import androidx.core.content.FileProvider
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
        onBookLongClick = { book -> showBookOptions(book) }
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
        title = "\u4E66\u67B6"

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

    // Bookshelf

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

    private fun showBookOptions(book: Book) {
        val items = arrayOf("\u4ECE\u4E66\u67B6\u79FB\u9664", "\u4ECE\u8BBE\u5907\u5220\u9664", "\u5206\u4EAB\u6587\u4EF6")
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> removeFromShelf(book)
                    1 -> deleteFromDevice(book)
                    2 -> shareFile(book)
                }
            }
            .setNegativeButton("\u53D6\u6D88", null)
            .show()
    }

    private fun removeFromShelf(book: Book) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.setFavorite(book.id, false)
            withContext(Dispatchers.Main) {
                loadBooks()
                Toast.makeText(this@BookshelfActivity, "\u5DF2\u4ECE\u4E66\u67B6\u79FB\u9664", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteFromDevice(book: Book) {
        AlertDialog.Builder(this)
            .setTitle("\u786E\u8BA4\u5220\u9664")
            .setMessage("\u786E\u5B9A\u8981\u5220\u9664 ${book.title} \u5417\uFF1F\u6587\u4EF6\u5C06\u4ECE\u8BBE\u5907\u4E2D\u6C38\u4E45\u5220\u9664\u3002")
            .setPositiveButton("\u5220\u9664") { _, _ ->
                val file = File(book.filePath)
                if (file.exists() && file.delete()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.deleteBook(book.id)
                        withContext(Dispatchers.Main) {
                            loadBooks()
                            Toast.makeText(this@BookshelfActivity, "\u5DF2\u5220\u9664", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "\u5220\u9664\u5931\u8D25", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("\u53D6\u6D88", null)
            .show()
    }

    private fun shareFile(book: Book) {
        val file = File(book.filePath)
        if (!file.exists()) {
            Toast.makeText(this, "\u6587\u4EF6\u4E0D\u5B58\u5728", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "\u5206\u4EAB ${book.title}"))
        } catch (e: Exception) {
            // fallback to Uri.fromFile
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "\u5206\u4EAB ${book.title}"))
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

    // Import

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
                Toast.makeText(this@BookshelfActivity, "\u5DF2\u6DFB\u52A0\u5230\u4E66\u67B6: ${book.title}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@BookshelfActivity, "\u65E0\u6CD5\u5BFC\u5165\u6B64\u6587\u4EF6", Toast.LENGTH_SHORT).show()
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

    // File browser

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
            if (entries.isEmpty()) binding.tvEmpty.text = "\u6B64\u76EE\u5F55\u4E3A\u7A7A\u6216\u6CA1\u6709\u652F\u6301\u7684\u7535\u5B50\u4E66"
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

    // Sort

    private fun setupSortSpinner() {
        val sortOptions = arrayOf("\u6700\u8FD1\u9605\u8BFB", "\u4E66\u540D", "\u4F5C\u8005", "\u4FEE\u6539\u65F6\u95F4")
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
            binding.btnSortOrder.text = if (sortAscending) "\u5347\u5E8F" else "\u964D\u5E8F"
            if (!isImportMode) loadBooks()
        }
    }

    // Permission

    private fun checkStoragePermission(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                onGranted()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("\u9700\u8981\u6587\u4EF6\u8BBF\u95EE\u6743\u9650")
                    .setMessage("Aurora \u9700\u8981\u8BBF\u95EE\u5B58\u50A8\u7A7A\u95F4\u624D\u80FD\u6D4F\u89C8\u7535\u5B50\u4E66\u6587\u4EF6\u3002\u8BF7\u6388\u4E88\u6240\u6709\u6587\u4EF6\u8BBF\u95EE\u6743\u9650\u3002")
                    .setPositiveButton("\u53BB\u8BBE\u7F6E") { _, _ ->
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton("\u53D6\u6D88", null)
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

    // Search

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(com.nous.aurora.R.menu.bookshelf_menu, menu)
        val searchItem = menu.findItem(com.nous.aurora.R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = "\u641C\u7D22\u4E66\u540D\u3001\u4F5C\u8005..."
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
