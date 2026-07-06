package com.nous.aurora.ui.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.nous.aurora.AuroraApp
import com.nous.aurora.data.model.BookFormat
import com.nous.aurora.data.model.ContentBlock
import com.nous.aurora.data.parser.MobiNative
import com.nous.aurora.data.parser.ParserFactory
import com.nous.aurora.ui.bookshelf.BookshelfActivity
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket

class DebugService : Service() {

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db get() = AuroraApp.instance.db
    private val json = Json { prettyPrint = false }

    companion object {
        const val DEBUG_FLAG_FILE = "/storage/emulated/0/Aurora/debug_enabled"
        const val DEBUG_PORT = 8765
        const val CHANNEL_ID = "aurora_debug"
        const val NOTIFICATION_ID = 1001

        fun isEnabled(): Boolean = File(DEBUG_FLAG_FILE).exists()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startServer()
    }

    override fun onDestroy() {
        serverSocket?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Aurora 调试服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Aurora 本地调试接口"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, BookshelfActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Aurora 调试模式")
            .setContentText("本地调试接口运行在端口 $DEBUG_PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startServer() {
        scope.launch {
            try {
                serverSocket = ServerSocket(DEBUG_PORT)
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                // Port in use or service stopped
            }
        }
    }

    private suspend fun handleClient(socket: java.net.Socket) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()
            val requestLine = reader.readLine() ?: return@withContext

            // Read headers
            val headers = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (!line.isNullOrBlank()) {
                val parts = line.split(": ", limit = 2)
                if (parts.size == 2) headers[parts[0].lowercase()] = parts[1]
                line = reader.readLine()
            }

            // Read body if content-length present
            val body = if (headers.containsKey("content-length")) {
                val length = headers["content-length"]!!.toInt()
                val buffer = CharArray(length)
                reader.read(buffer, 0, length)
                String(buffer)
            } else ""

            val parts = requestLine.split(" ")
            val method = parts[0]
            val path = parts[1]

            val responseBody = handleRequest(method, path, body)
            val responseBytes = responseBody.toByteArray()

            val header = """
                HTTP/1.1 200 OK
                Content-Type: application/json; charset=utf-8
                Content-Length: ${responseBytes.size}
                Connection: close
                Access-Control-Allow-Origin: *

            """.trimIndent().replace("\n", "\r\n") + "\r\n"

            output.write(header.toByteArray())
            output.write(responseBytes)
            output.flush()
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private suspend fun handleRequest(method: String, path: String, body: String): String {
        val pathOnly = path.substringBefore("?")
        val queryParams = parseQueryString(path.substringAfter("?", ""))

        return try {
            val jsonElement = when {
                pathOnly == "/status" -> buildJsonObject {
                    put("status", "ok")
                    put("debug", true)
                }
                pathOnly == "/native_status" -> buildJsonObject {
                    put("native_loaded", MobiNative.isLoaded())
                }
                pathOnly == "/books" -> {
                    val sortField = queryParams["sort"] ?: "last_read_at"
                    val ascending = queryParams["order"] == "asc"
                    val favOnly = queryParams["favorites"] == "1"
                    val books = withContext(Dispatchers.IO) {
                        db.getAllBooks(sortField, ascending, favOnly)
                    }
                    buildJsonObject {
                        put("count", books.size)
                        put("sort", sortField)
                        put("order", if (ascending) "asc" else "desc")
                        putJsonArray("books") {
                            books.forEach { book ->
                                addJsonObject {
                                    put("id", book.id)
                                    put("title", book.title)
                                    put("author", book.author)
                                    put("format", book.format.name)
                                    put("progress", book.lastParagraphIndex)
                                    put("total", book.totalParagraphs)
                                    put("last_read", book.lastReadAt)
                                    put("is_favorite", book.isFavorite)
                                }
                            }
                        }
                    }
                }
                pathOnly == "/scan" && method == "POST" -> {
                    val intent = Intent(this@DebugService, BookshelfActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("scan", true)
                    }
                    startActivity(intent)
                    buildJsonObject { put("status", "scan_started") }
                }
                pathOnly.startsWith("/book/") -> {
                    handleBookEndpoint(pathOnly, method, body)
                }
                pathOnly == "/import" && method == "POST" -> {
                    handleImport(body)
                }
                pathOnly == "/set_position" && method == "POST" -> {
                    handleSetPosition(body)
                }
                pathOnly == "/set_favorite" && method == "POST" -> {
                    handleSetFavorite(body)
                }
                pathOnly == "/list_dir" && method == "POST" -> {
                    handleListDir(body)
                }
                pathOnly == "/verify_progress" && method == "POST" -> {
                    handleVerifyProgress(body)
                }
                pathOnly == "/reparse" && method == "POST" -> {
                    handleReparse(body)
                }
                else -> buildJsonObject {
                    put("error", "unknown_endpoint")
                    put("path", pathOnly)
                }
            }
            json.encodeToString(JsonObject.serializer(), jsonElement as JsonObject)
        } catch (e: Exception) {
            json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("error", "internal_error")
                put("message", e.message ?: "unknown")
            })
        }
    }

    private suspend fun handleBookEndpoint(pathOnly: String, method: String, body: String): JsonObject {
        val rest = pathOnly.removePrefix("/book/")

        // /book/{id}/links
        if (rest.endsWith("/links")) {
            val bookId = rest.removeSuffix("/links").toLongOrNull()
                ?: return buildJsonObject { put("error", "invalid_book_id") }
            val book = withContext(Dispatchers.IO) { db.getBookById(bookId) }
                ?: return buildJsonObject { put("error", "book_not_found") }
            val parser = ParserFactory.getParser(book.format)
                ?: return buildJsonObject { put("error", "no_parser") }
            val result = try { parser.parse(book.filePath) }
            catch (e: Exception) {
                return buildJsonObject {
                    put("error", "parse_error")
                    put("message", e.message ?: "")
                }
            }
            return buildJsonObject {
                put("book_id", bookId)
                put("link_map_size", result.linkMap.size)
                putJsonArray("links") {
                    result.blocks.mapIndexedNotNull { i, b ->
                        if (b is ContentBlock.Link) {
                            addJsonObject {
                                put("index", i)
                                put("text", b.text)
                                put("href", b.href)
                                put("resolved_index", result.linkMap[b.href] ?: 0)
                            }
                        }
                    }
                }
            }
        }

        // /book/{id}/blocks or /book/{id}/blocks/{n}
        if (rest.contains("/blocks")) {
            val parts = rest.split("/blocks")
            val bookId = parts[0].toLongOrNull()
                ?: return buildJsonObject { put("error", "invalid_book_id") }
            val blockIndex = parts.getOrNull(1)?.removePrefix("/")?.toIntOrNull()
            val book = withContext(Dispatchers.IO) { db.getBookById(bookId) }
                ?: return buildJsonObject { put("error", "book_not_found") }
            val parser = ParserFactory.getParser(book.format)
                ?: return buildJsonObject { put("error", "no_parser") }
            val result = try { parser.parse(book.filePath) }
            catch (e: Exception) {
                return buildJsonObject {
                    put("error", "parse_error")
                    put("message", e.message ?: "")
                }
            }

            if (blockIndex != null) {
                if (blockIndex !in result.blocks.indices) {
                    return buildJsonObject {
                        put("error", "invalid_block_index")
                        put("max", result.blocks.size - 1)
                    }
                }
                val block = result.blocks[blockIndex]
                return buildJsonObject {
                    put("book_id", bookId)
                    put("block_index", blockIndex)
                    put("total_blocks", result.blocks.size)
                    put("block", blockToJson(block))
                }
            } else {
                return buildJsonObject {
                    put("book_id", bookId)
                    put("total_blocks", result.blocks.size)
                    put("toc_count", result.toc.size)
                    putJsonArray("blocks") {
                        result.blocks.take(100).forEachIndexed { i, block ->
                            addJsonObject {
                                put("index", i)
                                put("type", block::class.simpleName ?: "unknown")
                                put("preview", block.text.take(80))
                            }
                        }
                    }
                    if (result.blocks.size > 100) put("truncated", true)
                }
            }
        }

        // Plain /book/{id}
        val bookId = rest.toLongOrNull()
            ?: return buildJsonObject { put("error", "invalid_book_id") }
        val book = withContext(Dispatchers.IO) { db.getBookById(bookId) }
        return if (book == null) {
            buildJsonObject { put("error", "book_not_found") }
        } else {
            val bookmarks = withContext(Dispatchers.IO) { db.getBookmarks(bookId) }
            val annotations = withContext(Dispatchers.IO) { db.getAllAnnotations(bookId) }
            buildJsonObject {
                put("id", book.id)
                put("title", book.title)
                put("author", book.author)
                put("format", book.format.name)
                put("progress", book.lastParagraphIndex)
                put("total", book.totalParagraphs)
                put("bookmarks", bookmarks.size)
                put("annotations", annotations.size)
                put("is_favorite", book.isFavorite)
            }
        }
    }

    private suspend fun handleImport(body: String): JsonObject {
        val request = json.parseToJsonElement(body).jsonObject
        val filePath = request["path"]?.jsonPrimitive?.content
            ?: return buildJsonObject { put("error", "missing_path") }
        val file = File(filePath)
        if (!file.exists()) {
            return buildJsonObject {
                put("error", "file_not_found")
                put("path", filePath)
            }
        }
        val ext = file.extension.lowercase()
        val format = BookFormat.fromExtension(ext)
        if (format == BookFormat.UNKNOWN) {
            return buildJsonObject { put("error", "unsupported_format") }
        }
        val parser = ParserFactory.getParser(format)
            ?: return buildJsonObject { put("error", "no_parser") }
        val result = try { parser.parse(filePath) }
        catch (e: Exception) {
            return buildJsonObject {
                put("error", "parse_error")
                put("message", e.message ?: "")
            }
        }
        val book = com.nous.aurora.data.model.Book(
            filePath = filePath,
            title = result.metadata.title.ifBlank { file.nameWithoutExtension },
            author = result.metadata.author,
            format = format,
            totalParagraphs = result.blocks.size,
            fileModifiedAt = file.lastModified()
        )
        val id = withContext(Dispatchers.IO) { db.insertOrUpdateBook(book) }
        return buildJsonObject {
            put("status", "imported")
            put("book_id", id)
            put("title", book.title)
            put("blocks", result.blocks.size)
        }
    }

    private suspend fun handleSetPosition(body: String): JsonObject {
        val request = json.parseToJsonElement(body).jsonObject
        val bookId = request["book_id"]?.jsonPrimitive?.longOrNull
            ?: return buildJsonObject { put("error", "missing_book_id") }
        val pos = request["position"]?.jsonPrimitive?.intOrNull
            ?: return buildJsonObject { put("error", "missing_position") }
        withContext(Dispatchers.IO) { db.updateReadingProgress(bookId, pos) }
        val updated = withContext(Dispatchers.IO) { db.getBookById(bookId) }
        return buildJsonObject {
            put("status", "ok")
            put("book_id", bookId)
            put("saved_position", pos)
            put("verified_position", updated?.lastParagraphIndex ?: -1)
        }
    }

    private suspend fun handleSetFavorite(body: String): JsonObject {
        val request = json.parseToJsonElement(body).jsonObject
        val bookId = request["book_id"]?.jsonPrimitive?.longOrNull
            ?: return buildJsonObject { put("error", "missing_book_id") }
        val fav = request["favorite"]?.jsonPrimitive?.intOrNull
            ?: return buildJsonObject { put("error", "missing_favorite") }
        withContext(Dispatchers.IO) { db.setFavorite(bookId, fav != 0) }
        return buildJsonObject {
            put("status", "ok")
            put("book_id", bookId)
            put("is_favorite", fav != 0)
        }
    }

    private fun handleListDir(body: String): JsonObject {
        val request = json.parseToJsonElement(body).jsonObject
        val dirPath = request["path"]?.jsonPrimitive?.content
            ?: return buildJsonObject { put("error", "missing_path") }
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) {
            return buildJsonObject { put("error", "invalid_directory") }
        }
        val files = dir.listFiles()?.toList() ?: emptyList()
        val dirs = files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val supported = files.filter {
            it.isFile && it.extension.lowercase() in setOf("epub","pdf","mobi","azw","azw3","txt","md","markdown","fb2")
        }.sortedBy { it.name.lowercase() }
        val all = dirs + supported
        return buildJsonObject {
            put("path", dirPath)
            put("count", all.size)
            putJsonArray("entries") {
                all.forEach { f ->
                    addJsonObject {
                        put("name", f.name)
                        put("is_dir", f.isDirectory)
                        put("size", f.length())
                    }
                }
            }
        }
    }

    private suspend fun handleVerifyProgress(body: String): JsonObject {
        val request = json.parseToJsonElement(body).jsonObject
        val bookId = request["book_id"]?.jsonPrimitive?.longOrNull
            ?: return buildJsonObject { put("error", "missing_book_id") }
        val book = withContext(Dispatchers.IO) { db.getBookById(bookId) }
            ?: return buildJsonObject { put("error", "book_not_found") }
        return buildJsonObject {
            put("book_id", bookId)
            put("title", book.title)
            put("saved_position", book.lastParagraphIndex)
            put("total", book.totalParagraphs)
            put("last_read", book.lastReadAt)
        }
    }

    private suspend fun handleReparse(body: String): JsonObject {
        val request = json.parseToJsonElement(body).jsonObject
        val bookId = request["book_id"]?.jsonPrimitive?.longOrNull
            ?: return buildJsonObject { put("error", "missing_book_id") }
        val book = withContext(Dispatchers.IO) { db.getBookById(bookId) }
            ?: return buildJsonObject { put("error", "book_not_found") }
        val parser = ParserFactory.getParser(book.format)
            ?: return buildJsonObject { put("error", "no_parser") }
        val result = try { parser.parse(book.filePath) }
        catch (e: Exception) {
            return buildJsonObject {
                put("error", "parse_error")
                put("message", e.message ?: "")
            }
        }
        withContext(Dispatchers.IO) { db.updateTotalParagraphs(bookId, result.blocks.size) }
        val updatedBook = book.copy(totalParagraphs = result.blocks.size)
        withContext(Dispatchers.IO) { db.insertOrUpdateBook(updatedBook) }
        return buildJsonObject {
            put("status", "reparsed")
            put("book_id", bookId)
            put("title", book.title)
            put("blocks", result.blocks.size)
            put("toc_count", result.toc.size)
        }
    }

    private fun blockToJson(block: ContentBlock): JsonObject = when (block) {
        is ContentBlock.Paragraph -> buildJsonObject {
            put("type", "paragraph")
            put("text", block.text)
        }
        is ContentBlock.Heading -> buildJsonObject {
            put("type", "heading")
            put("level", block.level)
            put("text", block.text)
        }
        is ContentBlock.Link -> buildJsonObject {
            put("type", "link")
            put("text", block.text)
            put("href", block.href)
        }
        is ContentBlock.Image -> buildJsonObject {
            put("type", "image")
            put("alt", block.text)
            put("src", block.src)
        }
        is ContentBlock.Table -> buildJsonObject {
            put("type", "table")
            putJsonArray("headers") { block.headers.forEach { add(it) } }
            putJsonArray("rows") {
                block.rows.forEach { row ->
                    addJsonArray { row.forEach { add(it) } }
                }
            }
            put("text", block.text.take(200))
        }
        is ContentBlock.Formula -> buildJsonObject {
            put("type", "formula")
            put("display", block.display)
            put("text", block.text)
        }
        is ContentBlock.Code -> buildJsonObject {
            put("type", "code")
            put("language", block.language)
            put("text", block.text.take(100))
        }
        is ContentBlock.Separator -> buildJsonObject { put("type", "separator") }
        is ContentBlock.PageBreak -> buildJsonObject { put("type", "pagebreak") }
    }

    private fun parseQueryString(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { part ->
            val kv = part.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
    }
}
