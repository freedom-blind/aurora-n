package com.nous.aurora.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE book_id = :bookId AND paragraph_index = :paragraphIndex")
    suspend fun remove(bookId: Long, paragraphIndex: Int): Int

    @Query("DELETE FROM bookmarks WHERE book_id = :bookId AND locator_json = :locatorJson")
    suspend fun removeByLocator(bookId: Long, locatorJson: String): Int

    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId ORDER BY paragraph_index ASC, created_time ASC")
    suspend fun getByBook(bookId: Long): List<BookmarkEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE book_id = :bookId AND paragraph_index = :paragraphIndex LIMIT 1)")
    suspend fun hasBookmark(bookId: Long, paragraphIndex: Int): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE book_id = :bookId AND locator_json = :locatorJson LIMIT 1)")
    suspend fun hasBookmarkByLocator(bookId: Long, locatorJson: String): Boolean
}
