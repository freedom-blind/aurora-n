package com.nous.aurora.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface AnnotationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(annotation: AnnotationEntity): Long

    @Update
    suspend fun update(annotation: AnnotationEntity): Int

    @Query("UPDATE annotations SET text = :text, modified_time = :modifiedTime WHERE id = :id")
    suspend fun updateText(id: Long, text: String, modifiedTime: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT * FROM annotations WHERE book_id = :bookId AND paragraph_index = :paragraphIndex ORDER BY created_time DESC")
    suspend fun getByParagraph(bookId: Long, paragraphIndex: Int): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE book_id = :bookId ORDER BY paragraph_index ASC, created_time ASC")
    suspend fun getByBook(bookId: Long): List<AnnotationEntity>
}
