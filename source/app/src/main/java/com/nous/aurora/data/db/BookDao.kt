package com.nous.aurora.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity): Int

    @Transaction
    suspend fun insertOrUpdate(book: BookEntity): Long {
        val id = insert(book)
        return if (id == -1L) {
            // Already exists, update by file_path
            updateByPath(
                filePath = book.filePath,
                title = book.title,
                author = book.author,
                publisher = book.publisher,
                year = book.year,
                language = book.language,
                description = book.description,
                coverPath = book.coverPath,
                format = book.format,
                totalParagraphs = book.totalParagraphs,
                fileModifiedAt = book.fileModifiedAt
            )
            getIdByPath(book.filePath) ?: 0L
        } else {
            id
        }
    }

    @Query("""
        UPDATE books SET
            title = :title,
            author = :author,
            publisher = :publisher,
            year = :year,
            language = :language,
            description = :description,
            cover_path = :coverPath,
            format = :format,
            total_paragraphs = :totalParagraphs,
            file_modified_at = :fileModifiedAt
        WHERE file_path = :filePath
    """)
    suspend fun updateByPath(
        filePath: String,
        title: String,
        author: String,
        publisher: String,
        year: String,
        language: String,
        description: String,
        coverPath: String,
        format: String,
        totalParagraphs: Int,
        fileModifiedAt: Long
    ): Int

    @Query("SELECT id FROM books WHERE file_path = :path LIMIT 1")
    suspend fun getIdByPath(path: String): Long?

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE file_path = :path LIMIT 1")
    suspend fun getByPath(path: String): BookEntity?

    @Query("SELECT * FROM books ORDER BY last_read_at DESC, title ASC")
    fun getAllFlow(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY last_read_at DESC, title ASC")
    suspend fun getAll(): List<BookEntity>

    @Query("SELECT * FROM books WHERE is_favorite = 1 ORDER BY last_read_at DESC, title ASC")
    suspend fun getFavorites(): List<BookEntity>

    @Query("""
        SELECT * FROM books
        WHERE title LIKE '%' || :query || '%'
           OR author LIKE '%' || :query || '%'
           OR file_path LIKE '%' || :query || '%'
        ORDER BY last_read_at DESC
    """)
    suspend fun search(query: String): List<BookEntity>

    @Query("""
        UPDATE books SET
            last_paragraph_index = :paragraphIndex,
            last_read_at = :timestamp,
            locator_json = :locatorJson
        WHERE id = :bookId
    """)
    suspend fun updateReadingProgress(bookId: Long, paragraphIndex: Int, timestamp: Long = System.currentTimeMillis(), locatorJson: String = ""): Int

    @Query("UPDATE books SET total_paragraphs = :total WHERE id = :bookId")
    suspend fun updateTotalParagraphs(bookId: Long, total: Int): Int

    @Query("UPDATE books SET is_favorite = :favorite WHERE id = :bookId")
    suspend fun setFavorite(bookId: Long, favorite: Boolean): Int

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun delete(bookId: Long): Int
}
