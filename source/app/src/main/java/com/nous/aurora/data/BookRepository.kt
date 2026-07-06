package com.nous.aurora.data

import com.nous.aurora.data.db.AnnotationDao
import com.nous.aurora.data.db.AnnotationEntity
import com.nous.aurora.data.db.AuroraDatabase
import com.nous.aurora.data.db.BookDao
import com.nous.aurora.data.db.BookEntity
import com.nous.aurora.data.db.BookmarkDao
import com.nous.aurora.data.db.BookmarkEntity
import com.nous.aurora.data.model.Book
import com.nous.aurora.data.model.BookAnnotation
import com.nous.aurora.data.model.BookFormat
import com.nous.aurora.data.model.Bookmark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository layer that exposes domain models while using Room DAOs internally.
 * This replaces the raw SQLiteOpenHelper ([BookDatabase]) with industrial-grade Room.
 */
class BookRepository(database: AuroraDatabase) {

    private val bookDao: BookDao = database.bookDao()
    private val bookmarkDao: BookmarkDao = database.bookmarkDao()
    private val annotationDao: AnnotationDao = database.annotationDao()

    // ── Books ──

    suspend fun insertOrUpdateBook(book: Book): Long {
        return bookDao.insertOrUpdate(book.toEntity())
    }

    suspend fun getBookByPath(path: String): Book? {
        return bookDao.getByPath(path)?.toModel()
    }

    suspend fun getBookById(id: Long): Book? {
        return bookDao.getById(id)?.toModel()
    }

    suspend fun getAllBooks(
        sortField: String = "last_read_at",
        ascending: Boolean = false,
        favoritesOnly: Boolean = false
    ): List<Book> {
        val entities = if (favoritesOnly) {
            bookDao.getFavorites()
        } else {
            bookDao.getAll()
        }
        val sorted = when (sortField) {
            "title" -> if (ascending) entities.sortedBy { it.title.lowercase() }
                      else entities.sortedByDescending { it.title.lowercase() }
            "author" -> if (ascending) entities.sortedBy { it.author.lowercase() }
                       else entities.sortedByDescending { it.author.lowercase() }
            "file_modified_at" -> if (ascending) entities.sortedBy { it.fileModifiedAt }
                                 else entities.sortedByDescending { it.fileModifiedAt }
            else -> if (ascending) entities.sortedBy { it.lastReadAt }
                   else entities.sortedByDescending { it.lastReadAt }
        }
        // Tie-break for consistent ordering
        return sorted.map { it.toModel() }
    }

    fun getAllBooksFlow(): Flow<List<Book>> {
        return bookDao.getAllFlow().map { list -> list.map { it.toModel() } }
    }

    suspend fun searchBooks(query: String): List<Book> {
        return bookDao.search(query).map { it.toModel() }
    }

    suspend fun updateReadingProgress(bookId: Long, paragraphIndex: Int, locatorJson: String = "") {
        bookDao.updateReadingProgress(bookId, paragraphIndex, locatorJson = locatorJson)
    }

    suspend fun updateTotalParagraphs(bookId: Long, total: Int) {
        bookDao.updateTotalParagraphs(bookId, total)
    }

    suspend fun deleteBook(bookId: Long) {
        bookDao.delete(bookId)
    }

    suspend fun setFavorite(bookId: Long, favorite: Boolean) {
        bookDao.setFavorite(bookId, favorite)
    }

    // ── Bookmarks ──

    suspend fun addBookmark(bookmark: Bookmark): Long {
        return bookmarkDao.insert(bookmark.toEntity())
    }

    suspend fun removeBookmark(bookId: Long, paragraphIndex: Int) {
        bookmarkDao.remove(bookId, paragraphIndex)
    }

    suspend fun getBookmarks(bookId: Long): List<Bookmark> {
        return bookmarkDao.getByBook(bookId).map { it.toModel() }
    }

    suspend fun hasBookmark(bookId: Long, paragraphIndex: Int): Boolean {
        return bookmarkDao.hasBookmark(bookId, paragraphIndex)
    }

    // ── Annotations ──

    suspend fun addAnnotation(annotation: BookAnnotation): Long {
        return annotationDao.insert(annotation.toEntity())
    }

    suspend fun updateAnnotation(id: Long, text: String) {
        annotationDao.updateText(id, text)
    }

    suspend fun deleteAnnotation(id: Long) {
        annotationDao.delete(id)
    }

    suspend fun getAnnotationsForParagraph(bookId: Long, paragraphIndex: Int): List<BookAnnotation> {
        return annotationDao.getByParagraph(bookId, paragraphIndex).map { it.toModel() }
    }

    suspend fun getAllAnnotations(bookId: Long): List<BookAnnotation> {
        return annotationDao.getByBook(bookId).map { it.toModel() }
    }
}

// ── Entity → Model mappers ──

private fun BookEntity.toModel(): Book = Book(
    id = id,
    filePath = filePath,
    title = title,
    author = author,
    publisher = publisher,
    year = year,
    language = language,
    description = description,
    coverPath = coverPath,
    format = try { BookFormat.valueOf(format) } catch (_: IllegalArgumentException) { BookFormat.UNKNOWN },
    lastParagraphIndex = lastParagraphIndex,
    totalParagraphs = totalParagraphs,
    addedAt = addedAt,
    lastReadAt = lastReadAt,
    fileModifiedAt = fileModifiedAt,
    isFavorite = isFavorite,
    locatorJson = locatorJson
)

private fun Book.toEntity(): BookEntity = BookEntity(
    id = id,
    filePath = filePath,
    title = title,
    author = author,
    publisher = publisher,
    year = year,
    language = language,
    description = description,
    coverPath = coverPath,
    format = format.name,
    lastParagraphIndex = lastParagraphIndex,
    totalParagraphs = totalParagraphs,
    addedAt = addedAt,
    lastReadAt = lastReadAt,
    fileModifiedAt = fileModifiedAt,
    isFavorite = isFavorite,
    locatorJson = locatorJson
)

private fun BookmarkEntity.toModel(): Bookmark = Bookmark(
    id = id,
    bookId = bookId,
    paragraphIndex = paragraphIndex,
    createdTime = createdTime,
    locatorJson = locatorJson
)

private fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    bookId = bookId,
    paragraphIndex = paragraphIndex,
    createdTime = createdTime,
    locatorJson = locatorJson
)

private fun AnnotationEntity.toModel(): BookAnnotation = BookAnnotation(
    id = id,
    bookId = bookId,
    paragraphIndex = paragraphIndex,
    text = text,
    createdTime = createdTime,
    modifiedTime = modifiedTime,
    locatorJson = locatorJson
)

private fun BookAnnotation.toEntity(): AnnotationEntity = AnnotationEntity(
    id = id,
    bookId = bookId,
    paragraphIndex = paragraphIndex,
    text = text,
    createdTime = createdTime,
    modifiedTime = modifiedTime,
    locatorJson = locatorJson
)
